package io.github.nagsfov.ocwo.fluids;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

import java.util.PriorityQueue;

/**
 * OCWO water pressure (hydraulic head): shared rules and helpers. See 06-pression.md. The bodies of water and the
 * transfers themselves are in {@link WaterBodies}.
 * <ul>
 *   <li>Height of a block, in levels: y * 8 + level.</li>
 *   <li>Free surface: a non-full block of the fluid, or a full block with air (or a replaceable block) above.</li>
 *   <li>Free point: a non-full block of the fluid, or an empty cell beside or above a full block of the body
 *   (never below: falling stays with the classic flow, which keeps waterfalls as one column).</li>
 *   <li>Water moves from the highest free surface to the lowest free point, one level at a time, while they are at
 *   least 2 levels apart.</li>
 * </ul>
 */
public final class PressureSearch {

	private static final int MAX = FluidUtils.MAX_LEVEL;
	private static final Direction[] ALL_DIRS = Direction.values();

	private PressureSearch() {
	}

	/**
	 * Speed-up of the flow for a difference of height in levels: square root of the difference in blocks
	 * (1 block x1, 4 blocks x2, 9 x3, 16 x4), capped by {@code max}.
	 */
	public static int flowMultiplier(int diffLevels, int max) {
		int blocks = diffLevels / MAX;
		int m = (int) Math.sqrt(Math.max(1, blocks));
		return Math.max(1, Math.min(max, m));
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

	/** Result of an inspection: heights (y * 8 + level), Integer.MIN_VALUE when none; size in columns. */
	public record Inspection(int highHeight, int lowHeight, int columns, boolean complete) {
		public boolean balanced() {
			return complete && (lowHeight == Integer.MIN_VALUE || highHeight - lowHeight < 2);
		}
	}

	/** Inspects the body of fluid at {@code pos} without moving anything (the limit is no longer used). */
	public static Inspection inspect(ServerLevel world, BlockPos pos, int limit) {
		return CustomFluidTicks.get(world).bodies().inspect(pos);
	}

	public static void wakeAround(ServerLevel world, FlowingFluid fluid, BlockPos center) {
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
