package io.github.nagsfov.ocwo.fluids;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

import java.util.ArrayDeque;
import java.util.PriorityQueue;

/**
 * <b>Abstract reservoirs</b> (06-pression.md, "Réservoirs abstraits"). A large body of natural water at rest (at
 * least {@code ocwo:reservoir_min_blocks} blocks, 8 by default) is one tank: when the flow or the pressure takes water
 * from one of its blocks and the zone's reserve cannot pay, that block keeps its level and the body pays out of its
 * own surface. The highest surface blocks at rest go down, one level at a time, the nearest first, so the surface goes
 * down evenly (over at most {@link #MAX_SURFACE} surface blocks around the place where the water leaves). They are
 * changed quietly: they stay natural and at rest and nothing wakes up, so taking water from a lake does not wake the
 * whole lake. Exact: the water really leaves the body. Deterministic: fixed search order, no randomness.
 * <p>
 * A smaller body is ordinary water: the block really goes down and wakes up (the water flows). A body that a payment
 * brings under the threshold (one of its blocks empties) becomes ordinary water too.
 */
public final class WaterReservoirs {

	/** Most surface blocks looked at for one search (a 64 x 64 area of a flat lake). */
	public static final int MAX_SURFACE = 4096;
	/** Most blocks looked at to reach the surface from the block the water leaves. */
	private static final int MAX_CLIMB = 512;
	/** 3D search order: up first (towards the surface), then the sides, down last. */
	private static final Direction[] ORDER = {Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN};
	private static final Direction[] SIDES = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

	/** Diagnostics: searches, payments, levels paid, surface blocks looked at (since the server started). */
	public static long searches, payments, levelsPaid, visited, smallBodies, noSurface, planFails;
	/** Diagnostics: the last block that started a search. */
	public static volatile long lastSearchPos;

	private WaterReservoirs() {
	}

	/** Smallest body that is a reservoir ({@code ocwo:reservoir_min_blocks}; 0 = no reservoirs). */
	public static int minBlocks(ServerLevel level) {
		return OcwoFluidGameRules.RESERVOIR_MIN_BLOCKS == null ? 8 : level.getGameRules().get(OcwoFluidGameRules.RESERVOIR_MIN_BLOCKS);
	}

	/** A surface block that can go down: plain natural water at rest, with no water above it. */
	private record Cell(long pos, int y, int amount, int order) {
		int height() {
			return y * FluidUtils.MAX_LEVEL + amount;
		}
	}

	// the search of the current tick, reused by the next payments that start in the same body
	private static ServerLevel cacheLevel;
	private static long cacheTick = Long.MIN_VALUE;
	private static Fluid cacheFluid;
	private static LongOpenHashSet cacheBody;
	private static PriorityQueue<Cell> cacheSurface;

	private static boolean sameFluid(FluidState fs, Fluid fluid) {
		return !fs.isEmpty() && fs.getType().isSame(fluid);
	}

	/** Natural water of this fluid (part of a body). */
	private static boolean natural(ServerLevel level, BlockPos p, Fluid fluid) {
		return level.isLoaded(p) && sameFluid(level.getFluidState(p), fluid) && WaterRest.isNatural(level, p);
	}

	/** Natural water with no water of this fluid above it. */
	private static boolean isSurface(ServerLevel level, BlockPos p, Fluid fluid) {
		return natural(level, p, fluid) && !sameFluid(level.getFluidState(p.above()), fluid);
	}

	/** Plain water block (not waterlogged, not kelp...) of natural water at rest. */
	private static boolean canGoDown(ServerLevel level, BlockPos p) {
		return level.getBlockState(p).getBlock() instanceof LiquidBlock && WaterRest.isInert(level, p);
	}

	/**
	 * The flow takes {@code levels} from the natural water at pos and the zone's reserve cannot pay: if pos belongs to a
	 * body of natural water of at least {@link #minBlocks} blocks, the body's highest surface blocks at rest go down by
	 * that much (quietly) and pos keeps its level: returns true. Otherwise nothing changes: returns false.
	 */
	public static boolean pay(ServerLevel level, BlockPos pos, Fluid fluid, int levels) {
		int min = minBlocks(level);
		if (min <= 0 || levels <= 0 || !(fluid instanceof FlowingFluid ff)) return false;
		long now = level.getGameTime();
		if (cacheLevel != level || cacheTick != now || cacheFluid == null || !cacheFluid.isSame(fluid) || !inCachedBody(level, pos, fluid)) {
			if (!search(level, pos, fluid, min)) return false;
		}
		// plan: the highest surface blocks first (ties: the nearest), one level at a time
		Long2IntOpenHashMap plan = new Long2IntOpenHashMap();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		int left = levels;
		while (left > 0) {
			Cell c = cacheSurface.poll();
			if (c == null) break;
			int amount;
			if (plan.containsKey(c.pos)) {
				amount = plan.get(c.pos);
			} else {
				// still as found (something else may have changed it since the search, this tick)
				m.set(c.pos);
				FluidState fs = level.getFluidState(m);
				if (!sameFluid(fs, fluid) || fs.getAmount() != c.amount || !canGoDown(level, m)) continue;
				amount = c.amount;
			}
			amount--;
			left--;
			plan.put(c.pos, amount);
			if (amount > 0) {
				cacheSurface.add(new Cell(c.pos, c.y, amount, c.order));
			} else {
				// emptied: the body loses a block (under the threshold, it is ordinary water from the next search on)
				// and the block below becomes the surface
				m.set(c.pos).move(Direction.DOWN);
				if (canGoDown(level, m) && sameFluid(level.getFluidState(m), fluid)) {
					cacheSurface.add(new Cell(m.asLong(), m.getY(), level.getFluidState(m).getAmount(), c.order));
				}
			}
		}
		if (left > 0) {
			// the body cannot pay that much: nothing changes
			planFails++;
			cacheTick = Long.MIN_VALUE;
			return false;
		}
		// apply quietly: the blocks stay natural and at rest, nothing wakes up
		WaterRest.suppress(true);
		try {
			for (var e : plan.long2IntEntrySet()) {
				FluidUtils.setFluid(level, BlockPos.of(e.getLongKey()), ff, e.getIntValue(), false);
			}
		} finally {
			WaterRest.suppress(false);
		}
		payments++;
		levelsPaid += levels;
		return true;
	}

	// ------------------------------------------------------------------ inspection (debug screen and display)

	/** What the water at a position is, as a reservoir. */
	public enum Kind {
		/** No natural water there (no water, or water that a player or the flow changed). */
		NONE,
		/** Natural water, but fewer blocks than {@link #minBlocks}: ordinary water, it flows for real. */
		SMALL,
		/** Natural water with no surface found near it (under a ceiling): it flows for real. */
		NO_SURFACE,
		/** A reservoir: it pays out of its surface. */
		RESERVOIR
	}

	/**
	 * A reservoir as {@link #pay} would see it: its kind, its surface blocks (up to {@link #MAX_SURFACE}), how many of
	 * them can go down (plain water at rest), the lowest and highest surface block, and the surface blocks themselves
	 * (up to {@code maxCells}, for the display).
	 */
	public record Inspection(Kind kind, int surface, int canGoDown, int minY, int maxY, long[] cells) {
		static final Inspection NONE = new Inspection(Kind.NONE, 0, 0, 0, 0, new long[0]);
	}

	/** Same search as a payment, read only (no cache, no counters): what the body of natural water at pos is. */
	public static Inspection inspect(ServerLevel level, BlockPos pos, Fluid fluid, int maxCells) {
		if (!natural(level, pos, fluid)) return Inspection.NONE;
		int min = minBlocks(level);
		if (min <= 0 || !atLeast(level, pos, fluid, min)) return new Inspection(Kind.SMALL, 0, 0, 0, 0, new long[0]);
		// climb to the surface (3D, fixed order), then walk along it
		LongOpenHashSet body = new LongOpenHashSet();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		BlockPos start = null;
		body.add(pos.asLong());
		queue.add(pos);
		while (!queue.isEmpty() && body.size() < MAX_CLIMB) {
			BlockPos p = queue.poll();
			if (isSurface(level, p, fluid)) {
				start = p;
				break;
			}
			for (Direction d : ORDER) {
				BlockPos n = p.relative(d);
				if (body.contains(n.asLong()) || !natural(level, n, fluid)) continue;
				body.add(n.asLong());
				queue.add(n);
			}
		}
		if (start == null) return new Inspection(Kind.NO_SURFACE, 0, 0, 0, 0, new long[0]);
		LongOpenHashSet seen = new LongOpenHashSet();
		it.unimi.dsi.fastutil.longs.LongArrayList cells = new it.unimi.dsi.fastutil.longs.LongArrayList();
		queue.clear();
		seen.add(start.asLong());
		queue.add(start);
		int down = 0, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		while (!queue.isEmpty()) {
			BlockPos p = queue.poll();
			if (canGoDown(level, p)) down++;
			minY = Math.min(minY, p.getY());
			maxY = Math.max(maxY, p.getY());
			if (cells.size() < maxCells) cells.add(p.asLong());
			for (Direction d : SIDES) {
				if (seen.size() >= MAX_SURFACE) break;
				BlockPos side = p.relative(d);
				BlockPos next = null;
				for (int dy : new int[]{1, 0, -1}) {
					m.setWithOffset(side, 0, dy, 0);
					if (isSurface(level, m, fluid)) {
						next = m.immutable();
						break;
					}
				}
				if (next == null || !seen.add(next.asLong())) continue;
				queue.add(next);
			}
		}
		return new Inspection(Kind.RESERVOIR, seen.size(), down, minY, maxY, cells.toLongArray());
	}

	/** pos is in the body found by this tick's search: itself, or its column of natural water goes up into it. */
	private static boolean inCachedBody(ServerLevel level, BlockPos pos, Fluid fluid) {
		if (cacheBody.contains(pos.asLong())) return true;
		BlockPos.MutableBlockPos m = pos.mutable();
		for (int i = 0; i < MAX_CLIMB; i++) {
			m.move(Direction.UP);
			if (cacheBody.contains(m.asLong())) {
				cacheBody.add(pos.asLong());
				return true;
			}
			if (!natural(level, m, fluid)) return false;
		}
		return false;
	}

	/** Counts the body's blocks from pos (3D, fixed order) up to {@code min}: true if it has at least that many. */
	private static boolean atLeast(ServerLevel level, BlockPos pos, Fluid fluid, int min) {
		LongOpenHashSet seen = new LongOpenHashSet();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		seen.add(pos.asLong());
		queue.add(pos);
		while (!queue.isEmpty() && seen.size() < min) {
			BlockPos p = queue.poll();
			for (Direction d : ORDER) {
				BlockPos n = p.relative(d);
				if (seen.contains(n.asLong()) || !natural(level, n, fluid)) continue;
				seen.add(n.asLong());
				queue.add(n);
				if (seen.size() >= min) return true;
			}
		}
		return seen.size() >= min;
	}

	/**
	 * The search: (1) the body has at least {@code min} blocks; (2) the nearest surface block, up first; (3) the surface
	 * from there, block to block (a neighbouring column's surface one block up, level or down), breadth first, up to
	 * {@link #MAX_SURFACE} blocks: the ones that can go down are the candidates.
	 */
	private static boolean search(ServerLevel level, BlockPos pos, Fluid fluid, int min) {
		searches++;
		lastSearchPos = pos.asLong();
		if (!atLeast(level, pos, fluid, min)) {
			smallBodies++;
			return false;
		}
		LongOpenHashSet body = new LongOpenHashSet();
		// (2) climb to the surface
		BlockPos start = null;
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		body.add(pos.asLong());
		queue.add(pos);
		while (!queue.isEmpty() && start == null && body.size() < MAX_CLIMB) {
			BlockPos p = queue.poll();
			if (isSurface(level, p, fluid)) {
				start = p;
				break;
			}
			for (Direction d : ORDER) {
				BlockPos n = p.relative(d);
				if (body.contains(n.asLong()) || !natural(level, n, fluid)) continue;
				body.add(n.asLong());
				queue.add(n);
			}
		}
		if (start == null) {
			noSurface++;
			return false;
		}
		// (3) along the surface
		PriorityQueue<Cell> surface = new PriorityQueue<>((a, b) -> a.height() != b.height() ? Integer.compare(b.height(), a.height())
				: Integer.compare(a.order, b.order));
		LongOpenHashSet seen = new LongOpenHashSet();
		queue.clear();
		seen.add(start.asLong());
		queue.add(start);
		int order = 0;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		while (!queue.isEmpty()) {
			BlockPos p = queue.poll();
			body.add(p.asLong());
			if (!p.equals(pos) && canGoDown(level, p)) surface.add(new Cell(p.asLong(), p.getY(), level.getFluidState(p).getAmount(), order++));
			for (Direction d : SIDES) {
				if (seen.size() >= MAX_SURFACE) break;
				BlockPos side = p.relative(d);
				BlockPos next = null;
				for (int dy : new int[]{1, 0, -1}) {
					m.setWithOffset(side, 0, dy, 0);
					if (isSurface(level, m, fluid)) {
						next = m.immutable();
						break;
					}
				}
				if (next == null || !seen.add(next.asLong())) continue;
				queue.add(next);
			}
		}
		visited += seen.size();
		cacheLevel = level;
		cacheTick = level.getGameTime();
		cacheFluid = fluid;
		cacheBody = body;
		cacheSurface = surface;
		return true;
	}
}
