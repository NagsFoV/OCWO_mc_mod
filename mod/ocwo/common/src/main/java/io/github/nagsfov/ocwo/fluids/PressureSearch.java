package io.github.nagsfov.ocwo.fluids;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * OCWO water pressure (hydraulic head), version 3. See 06-pression.md.
 * <p>
 * Inside a connected body of one fluid, a pressure step moves water from the <b>highest free surface</b> to the
 * <b>lowest free point</b>, one level at a time, as long as their height difference is more than one level.
 * <ul>
 *   <li>Height of a block, in levels: y * 8 + level.</li>
 *   <li>Free surface: a non-full block of the fluid, or a full block with air (or a replaceable block) above.</li>
 *   <li>Free point: a non-full block of the fluid, or an empty cell beside or above a full block of the body
 *   (never below: falling stays with the classic flow, which keeps waterfalls as one column).</li>
 * </ul>
 * <b>v3, column search</b>: the body is explored as vertical <i>columns</i> of full, non-falling blocks (a column is
 * one node), breadth first. A tall vessel of w x w blocks is w x w nodes whatever its height, so the search covers
 * large bodies completely (v2 searched block by block, upward or downward first, and gave up in vessels larger than
 * a few blocks, which it then wrongly took for balanced). The search limit counts columns. A search that reaches
 * its limit is <b>incomplete</b>: never "balanced", it is retried later.
 * <p>
 * Flow rate: a column can carry as many levels per tick as it has blocks (a tunnel 1 high: 1 level per tick per
 * column of the tunnel, 2 high: 2). Every transfer reserves the columns of its path for the tick.
 */
public final class PressureSearch {

	private static final int MAX = FluidUtils.MAX_LEVEL;
	private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
	private static final Direction[] ALL_DIRS = Direction.values();
	/** Transfers done with the result of one search (each one revalidated), times the flow speed-up. */
	private static final int TRANSFERS_PER_SEARCH = 32;

	/**
	 * Speed-up of the flow for a difference of height in levels: square root of the difference in blocks
	 * (1 block x1, 4 blocks x2, 9 x3, 16 x4), capped by {@code max}.
	 */
	public static int flowMultiplier(int diffLevels, int max) {
		int blocks = diffLevels / MAX;
		int m = (int) Math.sqrt(Math.max(1, blocks));
		return Math.max(1, Math.min(max, m));
	}

	public enum Outcome {
		/** At least one level was moved. */
		MOVED,
		/** The whole body was explored and is balanced: the trigger can be dropped. */
		BALANCED,
		/** Saturated paths or incomplete search (body larger than the limit): retry later. */
		BLOCKED
	}

	private PressureSearch() {
	}

	public static boolean isFalling(FluidState fs) {
		return fs.hasProperty(FlowingFluid.FALLING) && fs.getValue(FlowingFluid.FALLING);
	}

	public static boolean isStillFull(FluidState fs, FlowingFluid fluid) {
		return fs.getType().isSame(fluid) && fs.getAmount() == MAX && !isFalling(fs);
	}

	/** Height of a block in levels: y * 8 + level. */
	public static int height(BlockPos pos, int level) {
		return pos.getY() * MAX + level;
	}

	/**
	 * Hook for a future air-pocket system: may this empty cell be filled by pressure?
	 * Always true for now (air is not simulated: water simply replaces it).
	 */
	public static boolean canPressureFill(ServerLevel world, BlockPos pos) {
		return true;
	}

	// ------------------------------------------------------------------ columns

	/** A vertical run of full, non-falling blocks of the fluid. */
	private record Column(int x, int z, int yBot, int yTop) {
		long key() {
			return BlockPos.asLong(x, yBot, z);
		}

		int capacity() {
			return yTop - yBot + 1;
		}

		BlockPos top() {
			return new BlockPos(x, yTop, z);
		}
	}

	private static Column columnAt(ServerLevel world, FlowingFluid fluid, BlockPos p) {
		BlockPos.MutableBlockPos m = p.mutable();
		int yBot = p.getY(), yTop = p.getY();
		BlockPos cur = p;
		while (true) {
			BlockPos below = cur.below();
			if (!world.isLoaded(below) || !isStillFull(world.getFluidState(below), fluid) || FluidUtils.isPathBlocked(world, cur, below)) break;
			cur = below;
			yBot--;
		}
		cur = p;
		while (true) {
			BlockPos above = cur.above();
			if (!world.isLoaded(above) || !isStillFull(world.getFluidState(above), fluid) || FluidUtils.isPathBlocked(world, cur, above)) break;
			cur = above;
			yTop++;
		}
		return new Column(m.getX(), m.getZ(), yBot, yTop);
	}

	/** A surface or a free point, with the column it was reached from. */
	private static final class Target {
		final BlockPos pos;
		int height;
		final long via;

		Target(BlockPos pos, int height, long via) {
			this.pos = pos;
			this.height = height;
			this.via = via;
		}
	}

	private record Search(List<Target> highs, List<Target> lows, Long2LongOpenHashMap parents,
	                      Long2ObjectOpenHashMap<Column> columns, boolean complete, boolean hitSaturated) {
	}

	/**
	 * Breadth-first search of the body of fluid containing the full block {@code start}, by columns.
	 *
	 * @param usage levels already carried this tick by each column (key); full columns are not crossed
	 * @param limit max number of columns
	 */
	private static Search search(ServerLevel world, FlowingFluid fluid, BlockPos start, Long2IntOpenHashMap usage, int limit) {
		List<Target> highs = new ArrayList<>();
		List<Target> lows = new ArrayList<>();
		Long2LongOpenHashMap parents = new Long2LongOpenHashMap();
		Long2ObjectOpenHashMap<Column> columns = new Long2ObjectOpenHashMap<>();
		LongOpenHashSet candidates = new LongOpenHashSet();
		ArrayDeque<Column> queue = new ArrayDeque<>();
		Column first = columnAt(world, fluid, start);
		columns.put(first.key(), first);
		parents.put(first.key(), first.key());
		queue.add(first);
		boolean hitSaturated = false;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		Column c;
		while ((c = queue.poll()) != null) {
			long key = c.key();
			// --- top: free surface and free point above the column
			BlockPos top = c.top();
			BlockPos above = top.above();
			if (world.isLoaded(above) && !FluidUtils.isPathBlocked(world, top, above)) {
				FluidState afs = world.getFluidState(above);
				if (afs.getType().isSame(fluid)) {
					if (!isFalling(afs) && afs.getAmount() < MAX && candidates.add(above.asLong())) {
						Target t = new Target(above.immutable(), height(above, afs.getAmount()), key);
						highs.add(t);
						lows.add(new Target(t.pos, t.height, key));
					}
				} else if (afs.isEmpty() && world.getBlockState(above).canBeReplaced(fluid)) {
					highs.add(new Target(top, height(top, MAX), key));
					if (canPressureFill(world, above) && candidates.add(above.asLong())) lows.add(new Target(above.immutable(), height(above, 0), key));
				}
			}
			// --- bottom: a non-full block of the fluid under the column is a free point
			BlockPos bottom = new BlockPos(c.x(), c.yBot(), c.z());
			BlockPos below = bottom.below();
			if (world.isLoaded(below) && !FluidUtils.isPathBlocked(world, bottom, below)) {
				FluidState bfs = world.getFluidState(below);
				if (bfs.getType().isSame(fluid) && !isFalling(bfs) && bfs.getAmount() < MAX && candidates.add(below.asLong())) {
					lows.add(new Target(below.immutable(), height(below, bfs.getAmount()), key));
				}
			}
			// --- sides: neighbouring columns, non-full blocks, empty cells (lowest one per side)
			for (Direction d : HORIZONTAL) {
				boolean emptyFound = false;
				int y = c.yBot();
				while (y <= c.yTop()) {
					p.set(c.x(), y, c.z());
					BlockPos from = p.immutable();
					BlockPos q = from.relative(d);
					if (!world.isLoaded(q) || FluidUtils.isPathBlocked(world, from, q)) {
						y++;
						continue;
					}
					FluidState qfs = world.getFluidState(q);
					if (isStillFull(qfs, fluid)) {
						Column n = columnAt(world, fluid, q);
						long nk = n.key();
						if (!columns.containsKey(nk)) {
							if (usage.get(nk) >= n.capacity()) {
								hitSaturated = true;
							} else if (columns.size() >= limit) {
								return new Search(highs, lows, parents, columns, false, hitSaturated);
							} else {
								columns.put(nk, n);
								parents.put(nk, key);
								queue.add(n);
							}
						}
						y = Math.max(y, n.yTop()) + 1;
						continue;
					}
					if (qfs.getType().isSame(fluid)) {
						if (!isFalling(qfs) && candidates.add(q.asLong())) {
							Target t = new Target(q, height(q, qfs.getAmount()), key);
							highs.add(t);
							lows.add(new Target(q, t.height, key));
						}
					} else if (!emptyFound && qfs.isEmpty() && world.getBlockState(q).canBeReplaced(fluid) && canPressureFill(world, q)) {
						emptyFound = true;
						if (candidates.add(q.asLong())) lows.add(new Target(q, height(q, 0), key));
					}
					y++;
				}
			}
		}
		return new Search(highs, lows, parents, columns, true, hitSaturated);
	}

	/** Column keys from {@code via} up to the start column. */
	private static LongArrayList path(Long2LongOpenHashMap parents, long via) {
		LongArrayList out = new LongArrayList();
		long k = via;
		for (int guard = 0; guard <= parents.size(); guard++) {
			out.add(k);
			long parent = parents.getOrDefault(k, k);
			if (parent == k) break;
			k = parent;
		}
		return out;
	}

	/** Key of the column of full blocks containing {@code pos} (its bottom block), or 0 if {@code pos} is not full. */
	public static long columnKey(ServerLevel world, FlowingFluid fluid, BlockPos pos) {
		if (!isStillFull(world.getFluidState(pos), fluid)) return 0L;
		return columnAt(world, fluid, pos).key();
	}

	// ------------------------------------------------------------------ inspection (debug, tests)

	/** Result of an inspection: heights (y * 8 + level), Integer.MIN_VALUE when none; size in columns. */
	public record Inspection(int highHeight, int lowHeight, int columns, boolean complete) {
		public boolean balanced() {
			return complete && (lowHeight == Integer.MIN_VALUE || highHeight - lowHeight < 2);
		}
	}

	/** Inspects the body of fluid at {@code pos} without moving anything. */
	public static Inspection inspect(ServerLevel world, BlockPos pos, int limit) {
		FluidState fs = world.getFluidState(pos);
		if (fs.isEmpty() || !(fs.getType() instanceof FlowingFluid fluid)) return new Inspection(Integer.MIN_VALUE, Integer.MIN_VALUE, 0, true);
		BlockPos start = null;
		if (isStillFull(fs, fluid)) start = pos;
		else if (isStillFull(world.getFluidState(pos.below()), fluid)) start = pos.below();
		if (start == null) return new Inspection(height(pos, fs.getAmount()), Integer.MIN_VALUE, 0, true);
		Search s = search(world, fluid, start, new Long2IntOpenHashMap(), limit);
		int high = s.highs().stream().mapToInt(t -> t.height).max().orElse(Integer.MIN_VALUE);
		int low = s.lows().stream().mapToInt(t -> t.height).min().orElse(Integer.MIN_VALUE);
		return new Inspection(high, low, s.columns().size(), s.complete());
	}

	// ------------------------------------------------------------------ step

	/**
	 * Pressure step for the body of fluid that contains the full block {@code trigger}: one search, then up to
	 * {@link #TRANSFERS_PER_SEARCH} one-level transfers from the highest surfaces to the lowest free points,
	 * each one revalidated against the world.
	 *
	 * @param usage    levels carried this tick by each column (updated)
	 * @param lowSide  receives a block next to the last filled low point (next trigger)
	 * @param balanced receives the cells of a body found completely balanced
	 * @param searched receives the keys of the columns explored (one search per body and per tick)
	 * @param maxMoves at most this many levels are moved (the rest of the tick's quota)
	 * @param flowMax  max speed-up of the flow (see {@link #flowMultiplier})
	 * @param movedOut receives the number of levels moved in [0] and of blocks of water explored in [1]
	 */
	public static Outcome step(ServerLevel world, FlowingFluid fluid, BlockPos trigger, Long2IntOpenHashMap usage,
	                           int limit, BlockPos.MutableBlockPos lowSide, LongOpenHashSet balanced, LongOpenHashSet searched,
	                           int maxMoves, int flowMax, int[] movedOut) {
		movedOut[0] = 0;
		movedOut[1] = 0;
		if (limit <= 0) return Outcome.BALANCED;
		if (!isStillFull(world.getFluidState(trigger), fluid)) return Outcome.BALANCED;
		Search s = search(world, fluid, trigger, usage, limit);
		searched.addAll(s.columns().keySet());
		int cells = 0;
		for (Column col : s.columns().values()) cells += col.capacity();
		movedOut[1] = cells;
		PriorityQueue<Target> highs = new PriorityQueue<>(Comparator.comparingInt((Target t) -> -t.height));
		PriorityQueue<Target> lows = new PriorityQueue<>(Comparator.comparingInt((Target t) -> t.height));
		highs.addAll(s.highs());
		lows.addAll(s.lows());
		boolean saturated = s.hitSaturated();
		int moved = 0;
		List<BlockPos> touched = new ArrayList<>();
		int maxTransfers = Math.min(maxMoves, TRANSFERS_PER_SEARCH * Math.max(1, flowMax));
		while (moved < maxTransfers && !highs.isEmpty() && !lows.isEmpty()) {
			Target hi = highs.peek(), lo = lows.peek();
			// a level only moves while the difference is at least 2: the water never overshoots the balance
			if (hi.height - lo.height < 2) break;
			int mult = flowMultiplier(hi.height - lo.height, flowMax);
			if (hi.pos.equals(lo.pos)) {
				lows.poll();
				continue;
			}
			// revalidate both ends against the world
			FluidState hfs = world.getFluidState(hi.pos);
			if (!hfs.getType().isSame(fluid) || hfs.getAmount() == 0 || height(hi.pos, hfs.getAmount()) != hi.height) {
				highs.poll();
				continue;
			}
			FluidState lfs = world.getFluidState(lo.pos);
			int lowLevel = lfs.getType().isSame(fluid) ? lfs.getAmount() : 0;
			if (lowLevel >= MAX || (lowLevel == 0 && !lfs.isEmpty()) || height(lo.pos, lowLevel) != lo.height) {
				lows.poll();
				continue;
			}
			// capacity of the columns on both paths
			LongArrayList pathHi = path(s.parents(), hi.via), pathLo = path(s.parents(), lo.via);
			LongOpenHashSet cols = new LongOpenHashSet(pathHi);
			cols.addAll(pathLo);
			boolean free = true;
			for (long k : cols) {
				Column col = s.columns().get(k);
				if (col != null && usage.get(k) >= col.capacity() * mult) {
					free = false;
					break;
				}
			}
			if (!free) {
				saturated = true;
				break;
			}
			// strict conservation: one level from the surface to the low point
			FluidUtils.setFluid(world, hi.pos, fluid, hfs.getAmount() - 1, false);
			FluidUtils.setFluid(world, lo.pos, fluid, lowLevel + 1, false);
			for (long k : cols) usage.addTo(k, 1);
			touched.add(hi.pos);
			touched.add(lo.pos);
			moved++;
			highs.poll();
			lows.poll();
			if (hfs.getAmount() - 1 > 0) {
				hi.height--;
				highs.add(hi);
			}
			if (lowLevel + 1 < MAX) {
				lo.height++;
				lows.add(lo);
			}
			Column via = s.columns().get(lo.via);
			if (via != null) lowSide.set(via.top());
		}
		movedOut[0] = moved;
		if (moved > 0) {
			for (BlockPos t : touched) wakeAround(world, fluid, t);
			return Outcome.MOVED;
		}
		if (saturated || !s.complete()) return Outcome.BLOCKED;
		// completely explored and balanced: its cells need no other step for a while
		for (Column col : s.columns().values()) {
			for (int y = col.yBot(); y <= col.yTop(); y++) balanced.add(BlockPos.asLong(col.x(), y, col.z()));
		}
		return Outcome.BALANCED;
	}

	private static void wakeAround(ServerLevel world, FlowingFluid fluid, BlockPos center) {
		int delay = fluid.getTickDelay(world);
		world.scheduleTick(center, fluid, delay);
		for (Direction dir : ALL_DIRS) {
			BlockPos n = center.relative(dir);
			if (world.getFluidState(n).getType().isSame(fluid)) {
				world.scheduleTick(n, fluid, delay);
			}
		}
	}

	/**
	 * Displaced fluid that found no room around a placed block is pushed to the free surface of the surrounding
	 * body of fluid (the surface of the lake rises), instead of staying trapped inside the placed block.
	 *
	 * @return the amount that could not be placed
	 */
	public static int pushToSurface(ServerLevel world, BlockPos origin, FlowingFluid fluid, int amount, int limit) {
		if (amount <= 0 || limit <= 0) return amount;
		LongOpenHashSet visited = new LongOpenHashSet(64, .5f);
		PriorityQueue<BlockPos> queue = new PriorityQueue<>((a, b) -> Integer.compare(b.getY(), a.getY()));
		visited.add(origin.asLong());
		for (Direction dir : ALL_DIRS) {
			BlockPos n = origin.relative(dir);
			if (visited.add(n.asLong()) && isStillFull(world.getFluidState(n), fluid)) queue.add(n);
		}
		int searched = 0;
		BlockPos p;
		while (amount > 0 && (p = queue.poll()) != null && searched < limit) {
			searched++;
			for (Direction dir : ALL_DIRS) {
				BlockPos q = p.relative(dir);
				if (!visited.add(q.asLong())) continue;
				if (FluidUtils.isPathBlocked(world, p, q)) continue;
				FluidState qFs = world.getFluidState(q);
				if (qFs.getType().isSame(fluid)) {
					if (isFalling(qFs)) continue;
					int lvl = qFs.getAmount();
					if (lvl == MAX) {
						queue.add(q);
					} else {
						int put = Math.min(amount, MAX - lvl);
						FluidUtils.setFluid(world, q, fluid, lvl + put, false);
						amount -= put;
					}
				} else if (dir == Direction.UP && qFs.isEmpty() && world.getBlockState(q).canBeReplaced(fluid)) {
					// Empty cell on top of the body: the surface rises.
					int put = Math.min(amount, MAX);
					FluidUtils.setFluid(world, q, fluid, put, false);
					amount -= put;
				}
				if (amount <= 0) break;
			}
		}
		return amount;
	}
}
