package io.github.nagsfov.ocwo.fluids;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Hydraulic pressure, complete at every tick (see 06-pression.md, "Simulation complète").
 * <p>
 * Every body of fluid that pressure has met is kept from one tick to the next: its <b>columns</b> (vertical runs of
 * full, non-falling blocks), the links between neighbouring columns, and the <b>targets</b> of each column (free
 * surfaces and free points around it). Block changes only update what is around them: a column is rescanned, its
 * links are recomputed, bodies are merged when a link appears, and checked for a split (in memory, without reading
 * the world) when a link disappears. Each tick, every body that has work moves water from its highest surface to its
 * lowest free point, one level at a time, as far as the openings allow (column height times the pressure speed-up),
 * with no quota and nothing left for later. Water beyond the water simulation distance is frozen and acts as a wall.
 */
public final class WaterBodies {

	private static final int MAX = FluidUtils.MAX_LEVEL;
	private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
	private static final Direction[] ALL_DIRS = Direction.values();
	private static final int RANGE_SWEEP_PERIOD = 20;

	private final ServerLevel world;
	/** Columns of each (x, z), at most a few. */
	private final Long2ObjectOpenHashMap<ArrayList<Col>> index = new Long2ObjectOpenHashMap<>();
	/** Blocks changed since the last flush (processed in order). */
	private final LongLinkedOpenHashSet pending = new LongLinkedOpenHashSet();
	/** Blocks where water moved and whose body must be found (and built if unknown). */
	private final LongLinkedOpenHashSet triggers = new LongLinkedOpenHashSet();
	/** Bodies with work (dirty targets, or surfaces at least 2 levels apart). */
	private final ObjectLinkedOpenHashSet<Body> work = new ObjectLinkedOpenHashSet<>();
	private final ObjectLinkedOpenHashSet<Body> toCheck = new ObjectLinkedOpenHashSet<>();
	private final ObjectLinkedOpenHashSet<Body> bodies = new ObjectLinkedOpenHashSet<>();
	/** Chunks in range this tick (1) or not (0), cache. */
	private final Long2ByteOpenHashMap rangeCache = new Long2ByteOpenHashMap();
	private long rangeTick = Long.MIN_VALUE;
	private int nextId;
	private boolean flushing;

	// statistics of the last tick (debug, tests, slow tick warning)
	public int lastTransfers, lastBodiesWorked, lastColumnsBuilt, lastChanges, lastSplits;
	private int transfers, bodiesWorked, columnsBuilt, changes, splits;

	public WaterBodies(ServerLevel world) {
		this.world = world;
	}

	// ------------------------------------------------------------------ data

	static final class Col {
		final int x, z;
		final FlowingFluid fluid;
		int yBot, yTop;
		Body body;
		boolean removed;
		final ArrayList<Col> adj = new ArrayList<>(4);
		/** All the targets around the column must be (re)computed (new column, merged or split body). */
		boolean targetsDirty = true;
		int usage;
		long usageTick = Long.MIN_VALUE;
		Col parent;
		int depth;

		Col(int x, int z, int yBot, int yTop, FlowingFluid fluid) {
			this.x = x;
			this.z = z;
			this.yBot = yBot;
			this.yTop = yTop;
			this.fluid = fluid;
		}

		int cap() {
			return yTop - yBot + 1;
		}

		boolean contains(int y) {
			return y >= yBot && y <= yTop;
		}
	}

	/**
	 * A target in a heap: a free surface (high) or a free point (low) at pos, reached through col. Checked against the
	 * world when it reaches the top of its heap (stale entries are dropped then).
	 */
	record Entry(int h, BlockPos pos, Col col, boolean high) {
	}

	static final class Body {
		final int id;
		final FlowingFluid fluid;
		final ObjectLinkedOpenHashSet<Col> cols = new ObjectLinkedOpenHashSet<>();
		final ObjectLinkedOpenHashSet<Col> dirty = new ObjectLinkedOpenHashSet<>();
		PriorityQueue<Entry> highs = new PriorityQueue<>(Comparator.comparingInt((Entry e) -> -e.h()));
		PriorityQueue<Entry> lows = new PriorityQueue<>(Comparator.comparingInt(Entry::h));
		boolean treeValid;
		boolean removed;
		/** The openings carried all they could this tick (the rest flows next tick). */
		boolean saturated;
		long processedTick = Long.MIN_VALUE;

		Body(int id, FlowingFluid fluid) {
			this.id = id;
			this.fluid = fluid;
		}
	}

	// ------------------------------------------------------------------ range

	/** Water simulation distance in chunks (game rule; -1 = the server's simulation distance). */
	public int distance() {
		int d = OcwoFluidGameRules.WATER_SIMULATION_DISTANCE == null ? -1 : world.getGameRules().get(OcwoFluidGameRules.WATER_SIMULATION_DISTANCE);
		return d < 0 ? world.getServer().getPlayerList().getSimulationDistance() : d;
	}

	/** Is this chunk within the water simulation distance of a player? */
	public boolean inRangeChunk(long chunkPos) {
		long now = world.getGameTime();
		if (now != rangeTick) {
			rangeTick = now;
			rangeCache.clear();
		}
		byte b = rangeCache.getOrDefault(chunkPos, (byte) -1);
		if (b >= 0) return b == 1;
		boolean in;
		int rule = OcwoFluidGameRules.WATER_SIMULATION_DISTANCE == null ? -1 : world.getGameRules().get(OcwoFluidGameRules.WATER_SIMULATION_DISTANCE);
		if (rule < 0) {
			// default: exactly the chunks that tick (the server's simulation distance, forced chunks...)
			in = world.shouldTickBlocksAt(chunkPos);
		} else {
			int cx = ChunkPos.getX(chunkPos), cz = ChunkPos.getZ(chunkPos);
			in = false;
			for (ServerPlayer p : world.players()) {
				int pcx = p.blockPosition().getX() >> 4, pcz = p.blockPosition().getZ() >> 4;
				if (Math.max(Math.abs(pcx - cx), Math.abs(pcz - cz)) <= rule) {
					in = true;
					break;
				}
			}
			in &= world.shouldTickBlocksAt(chunkPos);
		}
		rangeCache.put(chunkPos, (byte) (in ? 1 : 0));
		return in;
	}

	public boolean inRange(BlockPos pos) {
		return inRangeChunk(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
	}

	private boolean usable(BlockPos p) {
		return world.isLoaded(p) && inRange(p);
	}

	// ------------------------------------------------------------------ events

	/** A block changed (any block, fluid or not): its surroundings are updated at the next flush. */
	public void onChange(BlockPos pos) {
		pending.add(pos.asLong());
	}

	/** Water moved at {@code pos}: its body is found (built if unknown) and gets work. */
	public void trigger(BlockPos pos) {
		triggers.add(pos.asLong());
	}

	/** Pressure disabled: forget the pending changes (the bodies are rebuilt when it is turned back on). */
	public void clearPending() {
		if (pending.isEmpty() && triggers.isEmpty() && bodies.isEmpty()) return;
		pending.clear();
		triggers.clear();
		index.clear();
		bodies.clear();
		work.clear();
		toCheck.clear();
	}

	public int pendingCount() {
		return pending.size() + triggers.size();
	}

	// ------------------------------------------------------------------ index

	private static long xz(int x, int z) {
		return ((long) x << 32) ^ (z & 0xffffffffL);
	}

	private Col colAt(int x, int y, int z) {
		ArrayList<Col> list = index.get(xz(x, z));
		if (list == null) return null;
		for (Col c : list) if (c.contains(y)) return c;
		return null;
	}

	private void addToIndex(Col c) {
		index.computeIfAbsent(xz(c.x, c.z), k -> new ArrayList<>(1)).add(c);
	}

	private void removeFromIndex(Col c) {
		ArrayList<Col> list = index.get(xz(c.x, c.z));
		if (list != null) {
			list.remove(c);
			if (list.isEmpty()) index.remove(xz(c.x, c.z));
		}
	}

	private boolean isFullCell(BlockPos p, FlowingFluid fluid) {
		return usable(p) && PressureSearch.isStillFull(world.getFluidState(p), fluid) && !WaterRest.isInert(world, p);
	}

	/** Vertical run of full blocks through {@code y} (none: null). */
	private int[] span(int x, int y, int z, FlowingFluid fluid) {
		BlockPos p = new BlockPos(x, y, z);
		if (!isFullCell(p, fluid)) return null;
		int bot = y, top = y;
		BlockPos cur = p;
		while (true) {
			BlockPos b = cur.below();
			if (!isFullCell(b, fluid) || FluidUtils.isPathBlocked(world, cur, b)) break;
			cur = b;
			bot--;
		}
		cur = p;
		while (true) {
			BlockPos a = cur.above();
			if (!isFullCell(a, fluid) || FluidUtils.isPathBlocked(world, cur, a)) break;
			cur = a;
			top++;
		}
		return new int[]{bot, top};
	}

	private FlowingFluid fluidAt(BlockPos p) {
		FluidState fs = world.getFluidState(p);
		return fs.getType() instanceof FlowingFluid ff ? ff.getSource() instanceof FlowingFluid src ? src : ff : null;
	}

	/** Are two horizontally neighbouring columns connected (a pair of full cells with an open face)? */
	private boolean connected(Col a, Col b) {
		if (a.fluid != b.fluid) return false;
		int lo = Math.max(a.yBot, b.yBot), hi = Math.min(a.yTop, b.yTop);
		for (int y = lo; y <= hi; y++) {
			if (!FluidUtils.isPathBlocked(world, new BlockPos(a.x, y, a.z), new BlockPos(b.x, y, b.z))) return true;
		}
		return false;
	}

	private void link(Col a, Col b) {
		if (!a.adj.contains(b)) a.adj.add(b);
		if (!b.adj.contains(a)) b.adj.add(a);
	}

	// ------------------------------------------------------------------ building

	/**
	 * Creates the column through (x, y, z) if it is full and unknown, then explores and links everything connected to
	 * it that is not known yet (complete, no limit). Returns the column (known or new), or null.
	 */
	private Col build(int x, int y, int z, FlowingFluid fluid) {
		Col known = colAt(x, y, z);
		if (known != null) return known;
		int[] s = span(x, y, z, fluid);
		if (s == null) return null;
		Col first = newCol(x, z, s[0], s[1], fluid);
		Body body = newBody(fluid);
		attach(first, body);
		ArrayDeque<Col> queue = new ArrayDeque<>();
		queue.add(first);
		Col c;
		while ((c = queue.poll()) != null) {
			for (Direction d : HORIZONTAL) {
				int nx = c.x + d.getStepX(), nz = c.z + d.getStepZ();
				int y2 = c.yBot;
				while (y2 <= c.yTop) {
					BlockPos from = new BlockPos(c.x, y2, c.z), q = new BlockPos(nx, y2, nz);
					Col n = colAt(nx, y2, nz);
					if (n == null) {
						if (isFullCell(q, fluid) && !FluidUtils.isPathBlocked(world, from, q)) {
							int[] ns = span(nx, y2, nz, fluid);
							n = newCol(nx, nz, ns[0], ns[1], fluid);
							attach(n, body);
							queue.add(n);
						} else {
							y2++;
							continue;
						}
					}
					if (n.fluid == fluid && n != c) {
						if (connected(c, n)) {
							link(c, n);
							if (n.body != null && n.body != body) body = merge(body, n.body);
						}
						y2 = Math.max(y2, n.yTop) + 1;
					} else {
						y2++;
					}
				}
			}
		}
		work.add(body);
		return first;
	}

	private Col newCol(int x, int z, int yBot, int yTop, FlowingFluid fluid) {
		Col c = new Col(x, z, yBot, yTop, fluid);
		addToIndex(c);
		columnsBuilt++;
		return c;
	}

	private Body newBody(FlowingFluid fluid) {
		Body b = new Body(nextId++, fluid);
		bodies.add(b);
		return b;
	}

	private void attach(Col c, Body b) {
		c.body = b;
		b.cols.add(c);
		b.dirty.add(c);
		c.targetsDirty = true;
		b.treeValid = false;
	}

	/** Union: the smaller body joins the larger one; returns the result. */
	private Body merge(Body a, Body b) {
		if (a == b) return a;
		Body big = a.cols.size() >= b.cols.size() ? a : b, small = big == a ? b : a;
		for (Col c : small.cols) {
			c.body = big;
			big.cols.add(c);
			c.targetsDirty = true;
			big.dirty.add(c);
		}
		big.treeValid = false;
		small.cols.clear();
		small.dirty.clear();
		small.removed = true;
		bodies.remove(small);
		work.remove(small);
		toCheck.remove(small);
		work.add(big);
		return big;
	}

	// ------------------------------------------------------------------ local updates

	/** Applies the pending block changes (local updates, no full rescans). */
	public void flush() {
		if (flushing) return;
		flushing = true;
		try {
			while (!pending.isEmpty()) {
				long p = pending.removeFirstLong();
				changes++;
				update(BlockPos.of(p));
			}
			for (Body b : new ArrayList<>(toCheck)) checkSplit(b);
			toCheck.clear();
			while (!triggers.isEmpty()) {
				BlockPos p = BlockPos.of(triggers.removeFirstLong());
				FlowingFluid f = fluidAt(p);
				if (f == null) continue;
				Col c = build(p.getX(), p.getY(), p.getZ(), f);
				if (c != null && c.body != null) work.add(c.body);
			}
		} finally {
			flushing = false;
		}
	}

	private void update(BlockPos pos) {
		int x = pos.getX(), y = pos.getY(), z = pos.getZ();
		ArrayList<Col> list = index.get(xz(x, z));
		List<Col> affected = new ArrayList<>(2);
		if (list != null) for (Col c : list) if (y >= c.yBot - 1 && y <= c.yTop + 1) affected.add(c);
		FlowingFluid fluid = fluidAt(pos);
		if (affected.isEmpty() && (fluid == null || !isFullCell(pos, fluid))) {
			markTargetsAround(x, y, z);
			return;
		}
		// common case: one column that keeps its identity (unchanged, grown or shrunk at one end)
		if (affected.size() == 1 && fluid != null && affected.get(0).fluid == fluid) {
			Col a = affected.get(0);
			int lo = Math.min(y, a.yBot), hi = Math.max(y, a.yTop);
			int[] only = null;
			int count = 0;
			for (int yy = lo; yy <= hi; ) {
				int[] sp = span(x, yy, z, fluid);
				if (sp == null) {
					yy++;
					continue;
				}
				count++;
				only = sp;
				yy = sp[1] + 1;
			}
			if (count == 1) {
				updateInPlace(a, only[0], only[1]);
				markTargetsAround(x, y, z);
				return;
			}
		}
		// remove the affected columns, remember their neighbours
		ObjectLinkedOpenHashSet<Col> oldNbrs = new ObjectLinkedOpenHashSet<>();
		ObjectLinkedOpenHashSet<Body> oldBodies = new ObjectLinkedOpenHashSet<>();
		int lo = y, hi = y;
		for (Col c : affected) {
			lo = Math.min(lo, c.yBot);
			hi = Math.max(hi, c.yTop);
			if (fluid == null) fluid = c.fluid;
			oldBodies.add(c.body);
			for (Col n : c.adj) {
				n.adj.remove(c);
				if (!affected.contains(n)) oldNbrs.add(n);
			}
			c.adj.clear();
			removeCol(c);
		}
		// rescan the span: new columns
		List<Col> created = new ArrayList<>(2);
		if (fluid != null) {
			int yy = lo;
			while (yy <= hi) {
				int[] s = span(x, yy, z, fluid);
				if (s == null) {
					yy++;
					continue;
				}
				created.add(newCol(x, z, s[0], s[1], fluid));
				yy = s[1] + 1;
			}
		}
		// link them, find their body (merging neighbours), explore unknown water next to them
		for (Col c : created) {
			Body body = null;
			for (Direction d : HORIZONTAL) {
				int nx = x + d.getStepX(), nz = z + d.getStepZ();
				for (int y2 = c.yBot; y2 <= c.yTop; y2++) {
					Col n = colAt(nx, y2, nz);
					if (n == null) {
						BlockPos q = new BlockPos(nx, y2, nz);
						if (isFullCell(q, c.fluid) && !FluidUtils.isPathBlocked(world, new BlockPos(x, y2, z), q)) {
							n = build(nx, y2, nz, c.fluid);
						}
						if (n == null) continue;
					}
					if (n.body == null || n == c) continue;
					if (connected(c, n)) {
						link(c, n);
						body = body == null ? n.body : merge(body, n.body);
					}
					y2 = Math.max(y2, n.yTop);
				}
			}
			if (body == null) {
				Body old = null;
				for (Body b : oldBodies) if (!b.removed) old = b;
				body = old != null ? old : newBody(c.fluid);
			}
			attach(c, body);
			work.add(body);
		}
		// links lost: the bodies may have split
		for (Col n : oldNbrs) {
			if (n.removed || n.body == null) continue;
			boolean stillLinked = false;
			for (Col c : created) if (n.adj.contains(c)) stillLinked = true;
			if (!stillLinked) toCheck.add(n.body);
		}
		for (Body b : oldBodies) {
			if (b.removed) continue;
			if (b.cols.isEmpty()) dropBody(b);
			else if (created.size() != 1) toCheck.add(b);
		}
		markTargetsAround(x, y, z);
	}

	/** The column keeps its identity: new extents, links recomputed, the tree kept unless a tree link was lost. */
	private void updateInPlace(Col a, int bot, int top) {
		Body body = a.body;
		if (a.yBot == bot && a.yTop == top) return;
		a.yBot = bot;
		a.yTop = top;
		ArrayList<Col> now = new ArrayList<>(4);
		for (Direction d : HORIZONTAL) {
			int nx = a.x + d.getStepX(), nz = a.z + d.getStepZ();
			for (int y2 = a.yBot; y2 <= a.yTop; y2++) {
				Col n = colAt(nx, y2, nz);
				if (n == null) {
					BlockPos q = new BlockPos(nx, y2, nz);
					if (isFullCell(q, a.fluid) && !FluidUtils.isPathBlocked(world, new BlockPos(a.x, y2, a.z), q)) {
						n = build(nx, y2, nz, a.fluid);
					}
					if (n == null) continue;
				}
				if (n.body != null && n != a && !now.contains(n) && connected(a, n)) now.add(n);
				y2 = Math.max(y2, n.yTop);
			}
		}
		boolean lostTreeLink = false, lost = false;
		for (Col n : new ArrayList<>(a.adj)) {
			if (now.contains(n)) continue;
			a.adj.remove(n);
			n.adj.remove(a);
			lost = true;
			if (n.parent == a || a.parent == n) lostTreeLink = true;
		}
		for (Col n : now) {
			if (a.adj.contains(n)) continue;
			link(a, n);
			if (n.body != a.body) body = merge(a.body, n.body);
		}
		body = a.body;
		if (lost) toCheck.add(body);
		if (lostTreeLink) body.treeValid = false;
		work.add(body);
	}

	private void removeCol(Col c) {
		removeFromIndex(c);
		c.removed = true;
		if (c.body != null) {
			c.body.cols.remove(c);
			c.body.dirty.remove(c);
			c.body.treeValid = false;
			work.add(c.body);
		}
	}

	private void dropBody(Body b) {
		b.removed = true;
		bodies.remove(b);
		work.remove(b);
		toCheck.remove(b);
	}

	/** The targets that a changed block can create or change: the block itself and its 6 neighbours. */
	private void markTargetsAround(int x, int y, int z) {
		BlockPos p = new BlockPos(x, y, z);
		evalCell(p);
		for (Direction d : ALL_DIRS) evalCell(p.relative(d));
	}

	/** In memory: is the body still in one piece? Otherwise each piece becomes a body. */
	private void checkSplit(Body b) {
		if (b.removed || b.cols.isEmpty()) {
			if (!b.removed) dropBody(b);
			return;
		}
		ObjectLinkedOpenHashSet<Col> seen = new ObjectLinkedOpenHashSet<>();
		ArrayDeque<Col> q = new ArrayDeque<>();
		Col start = b.cols.first();
		seen.add(start);
		q.add(start);
		Col c;
		while ((c = q.poll()) != null) for (Col n : c.adj) if (n.body == b && seen.add(n)) q.add(n);
		if (seen.size() == b.cols.size()) return;
		splits++;
		// the reached part stays in b, the others get new bodies
		List<Col> rest = new ArrayList<>();
		for (Col col : b.cols) if (!seen.contains(col)) rest.add(col);
		for (Col col : rest) b.cols.remove(col);
		ObjectLinkedOpenHashSet<Col> left = new ObjectLinkedOpenHashSet<>(rest);
		while (!left.isEmpty()) {
			Body nb = newBody(b.fluid);
			Col s0 = left.first();
			q.add(s0);
			left.remove(s0);
			while ((c = q.poll()) != null) {
				attach(c, nb);
				for (Col n : c.adj) if (left.remove(n)) q.add(n);
			}
			work.add(nb);
		}
		b.treeValid = false;
		work.add(b);
	}

	// ------------------------------------------------------------------ targets

	/**
	 * Height of {@code pos} as a target of column {@code c} (high: free surface; low: free point), or
	 * Integer.MIN_VALUE if it is not one.
	 */
	private int targetHeight(BlockPos pos, Col c, boolean high) {
		if (c.removed || !usable(pos)) return Integer.MIN_VALUE;
		if (WaterRest.isInert(world, pos)) return Integer.MIN_VALUE; // water at rest: a wall
		FlowingFluid fluid = c.fluid;
		int px = pos.getX(), py = pos.getY(), pz = pos.getZ();
		if (px == c.x && pz == c.z) {
			if (py == c.yTop && high) {
				// full top with a free cell above: the surface is the top of the block
				BlockPos above = pos.above();
				if (!usable(above) || FluidUtils.isPathBlocked(world, pos, above)) return Integer.MIN_VALUE;
				FluidState afs = world.getFluidState(above);
				if (afs.isEmpty() && world.getBlockState(above).canBeReplaced(fluid)) return PressureSearch.height(pos, MAX);
				return Integer.MIN_VALUE;
			}
			if (py == c.yTop + 1) {
				BlockPos top = pos.below();
				if (FluidUtils.isPathBlocked(world, top, pos)) return Integer.MIN_VALUE;
				FluidState fs = world.getFluidState(pos);
				if (fs.getType().isSame(fluid)) {
					return !PressureSearch.isFalling(fs) && fs.getAmount() < MAX ? PressureSearch.height(pos, fs.getAmount()) : Integer.MIN_VALUE;
				}
				if (!high && fs.isEmpty() && world.getBlockState(pos).canBeReplaced(fluid) && PressureSearch.canPressureFill(world, pos)) {
					return PressureSearch.height(pos, 0);
				}
				return Integer.MIN_VALUE;
			}
			if (py == c.yBot - 1 && !high) {
				BlockPos bottom = pos.above();
				if (FluidUtils.isPathBlocked(world, bottom, pos)) return Integer.MIN_VALUE;
				FluidState fs = world.getFluidState(pos);
				if (fs.getType().isSame(fluid) && !PressureSearch.isFalling(fs) && fs.getAmount() < MAX) return PressureSearch.height(pos, fs.getAmount());
			}
			return Integer.MIN_VALUE;
		}
		if (Math.abs(px - c.x) + Math.abs(pz - c.z) != 1 || !c.contains(py)) return Integer.MIN_VALUE;
		BlockPos from = new BlockPos(c.x, py, c.z);
		if (FluidUtils.isPathBlocked(world, from, pos)) return Integer.MIN_VALUE;
		FluidState fs = world.getFluidState(pos);
		if (PressureSearch.isStillFull(fs, fluid)) return Integer.MIN_VALUE;
		if (fs.getType().isSame(fluid)) return !PressureSearch.isFalling(fs) ? PressureSearch.height(pos, fs.getAmount()) : Integer.MIN_VALUE;
		if (!high && fs.isEmpty() && world.getBlockState(pos).canBeReplaced(fluid) && PressureSearch.canPressureFill(world, pos)) {
			return PressureSearch.height(pos, 0);
		}
		return Integer.MIN_VALUE;
	}

	private void offer(BlockPos pos, Col c) {
		if (c == null || c.removed || c.body == null || c.targetsDirty) return;
		int h = targetHeight(pos, c, true);
		if (h != Integer.MIN_VALUE) c.body.highs.add(new Entry(h, pos, c, true));
		int l = targetHeight(pos, c, false);
		if (l != Integer.MIN_VALUE) c.body.lows.add(new Entry(l, pos, c, false));
		if (h != Integer.MIN_VALUE || l != Integer.MIN_VALUE) work.add(c.body);
	}

	/** Offers {@code pos} as a target to every column around it. */
	private void evalCell(BlockPos pos) {
		int x = pos.getX(), y = pos.getY(), z = pos.getZ();
		offer(pos, colAt(x, y, z));
		Col below = colAt(x, y - 1, z);
		if (below != null && below.yTop == y - 1) offer(pos, below);
		Col above = colAt(x, y + 1, z);
		if (above != null && above.yBot == y + 1) offer(pos, above);
		for (Direction d : HORIZONTAL) offer(pos, colAt(x + d.getStepX(), y, z + d.getStepZ()));
	}

	/** Every target around a column (new column, merged or split body). */
	private void computeTargets(Col c) {
		c.targetsDirty = false;
		BlockPos top = new BlockPos(c.x, c.yTop, c.z);
		offer(top, c);
		offer(top.above(), c);
		offer(new BlockPos(c.x, c.yBot - 1, c.z), c);
		for (Direction d : HORIZONTAL) {
			for (int y = c.yBot; y <= c.yTop; y++) offer(new BlockPos(c.x + d.getStepX(), y, c.z + d.getStepZ()), c);
		}
	}

	/** Heaps too large (stale entries): keep the valid ones, once each. */
	private void compact(Body b) {
		b.highs = compact(b.highs, b, Comparator.comparingInt((Entry e) -> -e.h()));
		b.lows = compact(b.lows, b, Comparator.comparingInt(Entry::h));
	}

	private PriorityQueue<Entry> compact(PriorityQueue<Entry> heap, Body b, Comparator<Entry> order) {
		PriorityQueue<Entry> out = new PriorityQueue<>(Math.max(16, heap.size() / 2), order);
		java.util.HashSet<Entry> seen = new java.util.HashSet<>();
		for (Entry e : heap) if (seen.add(e) && valid(e, b)) out.add(e);
		return out;
	}

	private void markAllDirty(Body b) {
		for (Col c : b.cols) {
			c.targetsDirty = true;
			b.dirty.add(c);
		}
	}

	private void refreshTargets(Body b) {
		if (!b.dirty.isEmpty()) {
			for (Col c : new ArrayList<>(b.dirty)) {
				if (c.removed || c.body != b) continue;
				computeTargets(c);
			}
			b.dirty.clear();
		}
		if (b.highs.size() + b.lows.size() > 16 * b.cols.size() + 1024) compact(b);
	}

	private boolean valid(Entry e, Body b) {
		return !e.col().removed && e.col().body == b && !e.col().targetsDirty && targetHeight(e.pos(), e.col(), e.high()) == e.h();
	}

	private Entry peek(PriorityQueue<Entry> heap, Body b) {
		Entry e;
		while ((e = heap.peek()) != null && !valid(e, b)) heap.poll();
		return e;
	}

	// ------------------------------------------------------------------ paths

	private void buildTree(Body b) {
		long stamp = world.getGameTime();
		for (Col c : b.cols) {
			c.parent = null;
			c.depth = -1;
		}
		ArrayDeque<Col> q = new ArrayDeque<>();
		for (Col root : b.cols) {
			if (root.depth >= 0) continue;
			root.depth = 0;
			q.add(root);
			Col c;
			while ((c = q.poll()) != null) {
				for (Col n : c.adj) {
					if (n.body == b && n.depth < 0) {
						n.depth = c.depth + 1;
						n.parent = c;
						q.add(n);
					}
				}
			}
		}
		b.treeValid = true;
	}

	/** Columns between a and b (both included) along the tree, or null if they are not connected. */
	private static List<Col> path(Col a, Col b) {
		List<Col> left = new ArrayList<>(), right = new ArrayList<>();
		while (a != b) {
			if (a == null || b == null) return null;
			if (a.depth >= b.depth) {
				left.add(a);
				a = a.parent;
			} else {
				right.add(b);
				b = b.parent;
			}
		}
		left.add(a);
		for (int i = right.size() - 1; i >= 0; i--) left.add(right.get(i));
		return left;
	}

	// ------------------------------------------------------------------ tick

	/** Runs every body with work, completely (each body once per tick). */
	public void tick(int flowMax) {
		transfers = bodiesWorked = columnsBuilt = changes = splits = 0;
		long now = world.getGameTime();
		if (now % RANGE_SWEEP_PERIOD == 0) sweepRange();
		flush();
		boolean again = true;
		while (again) {
			again = false;
			for (Body b : new ArrayList<>(work)) {
				if (b.removed || b.processedTick == now) continue;
				b.processedTick = now;
				bodiesWorked++;
				process(b, now, flowMax);
				again = true;
			}
			flush();
		}
		// bodies with nothing left to do leave the work list
		work.removeIf(b -> b.removed || b.processedTick == now && !hasWork(b));
		lastTransfers = transfers;
		lastBodiesWorked = bodiesWorked;
		lastColumnsBuilt = columnsBuilt;
		lastChanges = changes;
		lastSplits = splits;
	}

	private boolean hasWork(Body b) {
		if (b.removed) return false;
		if (!b.dirty.isEmpty()) return true;
		Entry hi = peek(b.highs, b), lo = peek(b.lows, b);
		return hi != null && lo != null && hi.h() - lo.h() >= 2;
	}

	private void process(Body b, long now, int flowMax) {
		b.saturated = false;
		while (!b.removed) {
			refreshTargets(b);
			Entry hi = peek(b.highs, b), lo = peek(b.lows, b);
			if (hi == null || lo == null || hi.h() - lo.h() < 2) return;
			if (hi.pos().equals(lo.pos())) {
				b.lows.poll();
				continue;
			}
			FlowingFluid fluid = b.fluid;
			FluidState hfs = world.getFluidState(hi.pos());
			FluidState lfs = world.getFluidState(lo.pos());
			int lowLevel = lfs.getType().isSame(fluid) ? lfs.getAmount() : 0;
			if (!b.treeValid) buildTree(b);
			List<Col> path = path(hi.col(), lo.col());
			if (path == null) {
				b.treeValid = false;
				toCheck.add(b);
				return;
			}
			int mult = PressureSearch.flowMultiplier(hi.h() - lo.h(), flowMax);
			for (Col c : path) {
				if (c.usageTick != now) {
					c.usageTick = now;
					c.usage = 0;
				}
				if (c.usage >= c.cap() * mult) {
					// the openings carry no more this tick: the rest is the next tick's flow (physics, not a quota)
					b.saturated = true;
					return;
				}
			}
			FluidUtils.setFluid(world, hi.pos(), fluid, hfs.getAmount() - 1, false);
			FluidUtils.setFluid(world, lo.pos(), fluid, lowLevel + 1, false);
			b.highs.poll();
			b.lows.poll();
			for (Col c : path) c.usage++;
			transfers++;
			io.github.nagsfov.ocwo.util.OcwoProfiler.countPressureStep(world, true);
			PressureSearch.wakeAround(world, fluid, hi.pos());
			PressureSearch.wakeAround(world, fluid, lo.pos());
			flush();
		}
	}

	/** Columns beyond the water simulation distance (or in unloaded chunks) are dropped: their water is frozen. */
	private void sweepRange() {
		for (Body b : bodies) {
			for (Col c : b.cols) {
				BlockPos p = new BlockPos(c.x, c.yBot, c.z);
				if (!usable(p)) pending.add(p.asLong());
			}
		}
	}

	// ------------------------------------------------------------------ inspection

	/** Body of the fluid at pos (built if unknown): highest surface, lowest free point, size. */
	public PressureSearch.Inspection inspect(BlockPos pos) {
		flush();
		FluidState fs = world.getFluidState(pos);
		if (fs.isEmpty() || !(fs.getType() instanceof FlowingFluid)) return new PressureSearch.Inspection(Integer.MIN_VALUE, Integer.MIN_VALUE, 0, true);
		FlowingFluid fluid = fluidAt(pos);
		Col c = build(pos.getX(), pos.getY(), pos.getZ(), fluid);
		if (c == null) c = build(pos.getX(), pos.getY() - 1, pos.getZ(), fluid);
		if (c == null || c.body == null) return new PressureSearch.Inspection(PressureSearch.height(pos, fs.getAmount()), Integer.MIN_VALUE, 0, true);
		Body b = c.body;
		refreshTargets(b);
		Entry hi = peek(b.highs, b), lo = peek(b.lows, b);
		return new PressureSearch.Inspection(hi == null ? Integer.MIN_VALUE : hi.h(), lo == null ? Integer.MIN_VALUE : lo.h(), b.cols.size(), true);
	}

	/** Bodies with work that were not processed this tick, plus unapplied changes: must be 0 after a tick. */
	public int deferred() {
		long now = world.getGameTime();
		int n = pendingCount();
		for (Body b : work) if (!b.removed && b.processedTick != now) n++;
		return n;
	}

	/** Is there a body with work in the column of pos or in a column next to it? (water at rest, going back to rest) */
	public boolean hasWorkAround(BlockPos pos) {
		Col c = colAt(pos.getX(), pos.getY(), pos.getZ());
		if (c != null && c.body != null && work.contains(c.body)) return true;
		for (Direction d : ALL_DIRS) {
			BlockPos n = pos.relative(d);
			Col o = colAt(n.getX(), n.getY(), n.getZ());
			if (o != null && o.body != null && work.contains(o.body)) return true;
		}
		return false;
	}

	/** Bodies with work that have a column inside the box (tests). */
	public int workIn(BlockPos a, BlockPos b) {
		int x0 = Math.min(a.getX(), b.getX()), x1 = Math.max(a.getX(), b.getX());
		int y0 = Math.min(a.getY(), b.getY()), y1 = Math.max(a.getY(), b.getY());
		int z0 = Math.min(a.getZ(), b.getZ()), z1 = Math.max(a.getZ(), b.getZ());
		int n = 0;
		for (Body body : work) {
			if (body.removed) continue;
			for (Col c : body.cols) {
				if (c.x >= x0 && c.x <= x1 && c.z >= z0 && c.z <= z1 && c.yTop >= y0 && c.yBot <= y1) {
					n++;
					break;
				}
			}
		}
		return n;
	}

	/** State of the body at pos (tests). */
	public String debugBody(BlockPos pos) {
		Col c = colAt(pos.getX(), pos.getY(), pos.getZ());
		if (c == null) return "no column at " + pos.toShortString();
		Body b = c.body;
		Entry hi = peek(b.highs, b), lo = peek(b.lows, b);
		return "body " + b.id + ": " + b.cols.size() + " columns, in work " + work.contains(b) + ", processed " + b.processedTick
				+ " (now " + world.getGameTime() + "), saturated " + b.saturated + ", dirty " + b.dirty.size() + ", heap high "
				+ (hi == null ? "-" : hi.h() + "@" + hi.pos().toShortString()) + ", heap low " + (lo == null ? "-" : lo.h() + "@" + lo.pos().toShortString())
				+ ", tree " + b.treeValid + ", removed " + b.removed;
	}

	public int bodyCount() {
		return bodies.size();
	}

	public int columnCount() {
		int n = 0;
		for (Body b : bodies) n += b.cols.size();
		return n;
	}

	public int workCount() {
		return work.size();
	}

	/** Sizes of the largest bodies (columns), for diagnostics. */
	public List<int[]> largest(int n) {
		List<int[]> out = new ArrayList<>();
		for (Body b : bodies) out.add(new int[]{b.id, b.cols.size(), work.contains(b) ? 1 : 0});
		out.sort((a, c) -> Integer.compare(c[1], a[1]));
		return out.subList(0, Math.min(n, out.size()));
	}
}
