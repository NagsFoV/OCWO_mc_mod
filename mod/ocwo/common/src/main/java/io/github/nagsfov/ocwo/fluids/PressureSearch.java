package io.github.nagsfov.ocwo.fluids;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

import java.util.PriorityQueue;

/**
 * OCWO water pressure (hydraulic head), version 2. See 06-pression.md.
 * <p>
 * Inside a connected body of one fluid, one pressure step moves one level from the <b>highest free surface</b>
 * to the <b>lowest free point</b>, as long as their height difference is more than one level.
 * <ul>
 *   <li>Height of a block, in levels: y * 8 + level.</li>
 *   <li>Free surface: a non-full block of the fluid, or a full block whose top is not the fluid.</li>
 *   <li>Free point: a non-full block of the fluid, or an empty cell beside or above a full block of the body
 *   (never below: falling stays with the classic flow, which keeps waterfalls as one column).</li>
 * </ul>
 * Both searches go through the full, non-falling blocks of the fluid only: the surface search upward first,
 * the free-point search downward first (so rooms fill layer by layer, from the bottom).
 * <p>
 * Flow rate: each full block can carry at most {@link #CAPACITY_PER_TICK} level per tick. A step reserves the
 * blocks of its path; saturated blocks are blocked for the next searches of the same tick. So an opening of one
 * block lets about one level per tick through, an opening of two blocks about two.
 */
public final class PressureSearch {

	/** Levels a full block can carry per tick. */
	public static final int CAPACITY_PER_TICK = 1;

	private static final int MAX = FluidUtils.MAX_LEVEL;
	private static final Direction[] ALL_DIRS = Direction.values();

	public enum Outcome {
		/** One level was moved. */
		MOVED,
		/** Balanced (or nothing to do): the trigger can be dropped. */
		BALANCED,
		/** Could not run because of saturated blocks: retry later. */
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

	/** A found target: its position, height and the full block of the body it was reached from. */
	private record Target(BlockPos pos, int height, BlockPos via) {
	}

	/** Search result: best target, parents of the visited full blocks, and whether saturation blocked the way. */
	private record Search(Target target, Long2LongOpenHashMap parents, boolean hitSaturated, LongOpenHashSet seen) {
	}

	/**
	 * Best-first search from {@code start} through the full, non-falling blocks of {@code fluid}.
	 * The search never stops at the first target found: it keeps the best one within the limit.
	 *
	 * @param upward true: find the highest free surface; false: find the lowest free point
	 */
	private static Search search(ServerLevel world, FlowingFluid fluid, BlockPos start, boolean upward,
	                             LongOpenHashSet saturated, int limit) {
		Long2LongOpenHashMap parents = new Long2LongOpenHashMap(64, .5f);
		LongOpenHashSet seen = new LongOpenHashSet(64, .5f);
		PriorityQueue<BlockPos> queue = upward
				? new PriorityQueue<>((a, b) -> Integer.compare(b.getY(), a.getY()))
				: new PriorityQueue<>((a, b) -> Integer.compare(a.getY(), b.getY()));
		seen.add(start.asLong());
		parents.put(start.asLong(), start.asLong());
		queue.add(start);
		boolean hitSaturated = false;
		Target best = null;
		int ties = 0;
		int searched = 0;
		BlockPos p;
		while ((p = queue.poll()) != null && searched < limit) {
			searched++;
			if (upward && !world.getFluidState(p.above()).getType().isSame(fluid)) {
				// A full block whose top is not the fluid is a free surface.
				Target candidate = new Target(p, height(p, MAX), p);
				if (best == null || candidate.height() > best.height()) {
					best = candidate;
					ties = 1;
				} else if (candidate.height() == best.height() && world.getRandom().nextInt(++ties) == 0) {
					best = candidate;
				}
			}
			for (Direction dir : ALL_DIRS) {
				BlockPos q = p.relative(dir);
				long ql = q.asLong();
				if (seen.contains(ql)) continue;
				if (FluidUtils.isPathBlocked(world, p, q)) continue;
				FluidState qFs = world.getFluidState(q);
				Target candidate = null;
				if (qFs.getType().isSame(fluid)) {
					if (isFalling(qFs)) continue;
					int lvl = qFs.getAmount();
					seen.add(ql);
					if (lvl == MAX) {
						if (saturated.contains(ql)) {
							hitSaturated = true;
							continue;
						}
						parents.put(ql, p.asLong());
						queue.add(q);
						continue;
					}
					candidate = new Target(q, height(q, lvl), p);
				} else if (!upward && dir != Direction.DOWN && qFs.isEmpty()) {
					BlockState qState = world.getBlockState(q);
					if (qState.canBeReplaced(fluid) && canPressureFill(world, q)) {
						seen.add(ql);
						candidate = new Target(q, height(q, 0), p);
					}
				}
				if (candidate == null) continue;
				boolean better = best == null
						|| (upward ? candidate.height() > best.height() : candidate.height() < best.height());
				if (better) {
					best = candidate;
					ties = 1;
				} else if (candidate.height() == best.height() && world.getRandom().nextInt(++ties) == 0) {
					best = candidate;
				}
			}
		}
		return new Search(best, parents, hitSaturated, seen);
	}

	/** Debug: heights (y * 8 + level) of the highest free surface and the lowest free point of a body of fluid. */
	public record Inspection(int highHeight, int lowHeight) {
	}

	/**
	 * Debug (no transfer): highest free surface and lowest free point of the body of fluid at {@code pos}
	 * (lowHeight = Integer.MIN_VALUE when there is none). A non-full block with no full block under it is alone.
	 */
	public static Inspection inspect(ServerLevel world, BlockPos pos, int limit) {
		FluidState fs = world.getFluidState(pos);
		if (fs.isEmpty() || !(fs.getType() instanceof FlowingFluid fluid)) return new Inspection(Integer.MIN_VALUE, Integer.MIN_VALUE);
		BlockPos start = null;
		if (isStillFull(fs, fluid)) start = pos;
		else if (isStillFull(world.getFluidState(pos.below()), fluid)) start = pos.below();
		if (start == null) return new Inspection(height(pos, fs.getAmount()), Integer.MIN_VALUE);
		LongOpenHashSet none = new LongOpenHashSet();
		Search up = search(world, fluid, start, true, none, limit);
		Search down = search(world, fluid, start, false, none, limit);
		int high = up.target() == null ? height(start, MAX) : up.target().height();
		int low = down.target() == null ? Integer.MIN_VALUE : down.target().height();
		return new Inspection(high, low);
	}

	/**
	 * One pressure step for the body of fluid that contains the full block {@code trigger}.
	 *
	 * @param saturated full blocks that already carried their capacity this tick (updated by this call)
	 * @param lowSide   receives the full block next to the low point when a level was moved (next trigger)
	 * @param balanced  receives the blocks visited when the body is found balanced (they need no other step soon)
	 */
	public static Outcome step(ServerLevel world, FlowingFluid fluid, BlockPos trigger, LongOpenHashSet saturated,
	                           int limit, BlockPos.MutableBlockPos lowSide, LongOpenHashSet balanced) {
		if (limit <= 0) return Outcome.BALANCED;
		if (!isStillFull(world.getFluidState(trigger), fluid)) return Outcome.BALANCED;
		if (saturated.contains(trigger.asLong())) return Outcome.BLOCKED;

		// Low point first: when there is none, the surface search is not needed.
		Search down = search(world, fluid, trigger, false, saturated, limit);
		Target low = down.target();
		if (low == null) {
			if (down.hitSaturated()) return Outcome.BLOCKED;
			balanced.addAll(down.seen());
			return Outcome.BALANCED;
		}
		Search up = search(world, fluid, trigger, true, saturated, limit);
		Target high = up.target();
		if (high == null || high.height() - low.height() < 2 || high.pos().equals(low.pos())) {
			if (up.hitSaturated() || down.hitSaturated()) return Outcome.BLOCKED;
			balanced.addAll(down.seen());
			balanced.addAll(up.seen());
			return Outcome.BALANCED;
		}

		// Strict conservation: one level taken at the surface, one level added at the low point.
		FluidState highFs = world.getFluidState(high.pos());
		FluidState lowFs = world.getFluidState(low.pos());
		if (!highFs.getType().isSame(fluid) || highFs.getAmount() == 0) return Outcome.BLOCKED;
		int lowLevel = lowFs.getType().isSame(fluid) ? lowFs.getAmount() : 0;
		if (lowLevel >= MAX || (lowLevel == 0 && !lowFs.isEmpty())) return Outcome.BLOCKED;
		FluidUtils.setFluid(world, high.pos(), fluid, highFs.getAmount() - 1, false);
		FluidUtils.setFluid(world, low.pos(), fluid, lowLevel + 1, false);

		// Reserve the capacity of both paths (trigger -> surface, trigger -> low point).
		reserve(up.parents(), high.via(), saturated);
		reserve(down.parents(), low.via(), saturated);

		wakeAround(world, fluid, high.pos());
		wakeAround(world, fluid, low.pos());
		lowSide.set(low.via());
		return Outcome.MOVED;
	}

	private static void reserve(Long2LongOpenHashMap parents, BlockPos from, LongOpenHashSet saturated) {
		long p = from.asLong();
		for (int guard = 0; guard <= parents.size(); guard++) {
			saturated.add(p); // CAPACITY_PER_TICK = 1: one transfer saturates the block for this tick
			long parent = parents.getOrDefault(p, p);
			if (parent == p) break;
			p = parent;
		}
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
