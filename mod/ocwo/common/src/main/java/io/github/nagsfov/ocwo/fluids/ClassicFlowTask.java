package io.github.nagsfov.ocwo.fluids;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import io.github.nagsfov.ocwo.util.Vec2F;
import io.github.nagsfov.ocwo.Ocwo;

public final class ClassicFlowTask extends AbstractFluidTask {

	private final BlockState state;
	private final FluidState initialFluidState;
	private final VoxelShape shape;
	private final int[] dirPotential = new int[DIR_LEN];
	private final boolean wlLimited;

	private int level;

	public ClassicFlowTask(ServerLevel world, BlockPos pos, FlowingFluid fluid, BlockState bs, FluidState fs) {
		super(pos, fluid, world);
		this.initialFluidState = fs;
		this.state = bs;
		this.shape = this.state.getShape(world, pos);
		this.wlLimited = FluidUtils.checkForWLLimit(bs, fluid);
		this.level = this.initialFluidState.getAmount();
	}

	@Override
	public void run() {
		fluidTicks.countTask();
		// OCWO: porous blocks around moving water start soaking it up
		fluidTicks.moisture().activateAround(pos);
		flowRun();
	}

	private void flowRun() {
		boolean moved = false;
		int delta = canFlow(Direction.DOWN);
		if (delta > 0) {
			if (!FluidUtils.FLUID_CHUNK_LAYER) {
				BlockPos to = this.pos.below();
				BlockState toState = world.getBlockState(to);
				if (wlLimited || FluidUtils.checkForWLLimit(toState, this.fluid)) {
					if (delta != FluidUtils.MAX_LEVEL) return;
				} else if (this.level == delta && delta > 1) {
					delta--;
				}
				flow(pos, state, toState, to, Direction.DOWN, delta);
			} else {
				if (this.level == delta && delta > 1) {
					delta--;
				}
				flow(Direction.DOWN, delta);
			}
			moved = true;
		}
		if (this.level == 0 || wlLimited) return;
		int n = 0;
		for (int i = 0; i < DIR_LEN; i++) {
			Direction dir = randDirs[i];
			delta = canFlow(dir) / 2;
			if (delta > 0) {
				dirPotential[i] = delta;
				n++;
			}
		}
		if (n != 0) {
			for (int i = 0; i < DIR_LEN; i++) {
				int d = dirPotential[i] / n;
				if (d == 0) {
					continue;
				}

				if (d > this.level) {
					d = this.level;
				}
				Direction dir = randDirs[i];
				flow(dir, d);
				moved = true;
			}
			for (int i = 0; i < DIR_LEN; i++) {
				Direction dir = randDirs[i];
				int d = canFlow(dir) / 2;
				if (d > 0) {
					flow(dir, d);
					moved = true;
					break;
				}
			}
		}

		// OCWO: pressure, only when ordinary flow did nothing (keeps falling columns and equalization intact)
		if (!moved && !isFalling(initialFluidState)) {
			pressure();
		}

		// */
		if (!moved) {
			if (!haveFluidOnTop(pos, state, shape)) {
				Vec2F slope = FluidUtils.detectSlope(fluid, world, pos, initialFluidState, world);
				if (slope.lengthSquared() > FluidUtils.FLOW_THRESHOLD_SQR) {
					moved = slopeFlow(slope);
				} else {
					equalize();
				}
			}
		}
		if (!moved) {
			if (initialFluidState.getValue(FlowingFluid.FALLING)) {
				setFluid(pos, state, initialFluidState, level, false);
			} else {
				equalize();
			}
		}
	}

	private void flow(Direction dir, int amount) {
		BlockPos to = pos.relative(dir);
		flow(pos, state, getBlockState(to), to, dir, amount);
	}

	private void flow(BlockPos from, BlockState fromState, BlockState toState, BlockPos to, Direction dir, int amount) {
		FluidState toFs = getFluidState(to);
		this.level -= amount;
		setFluid(from, fromState, getFluidState(from), this.level, dir == Direction.DOWN);

		if (isThis(toFs)) {
			int toLevel = toFs.getAmount() + amount;
			setFluid(to, toState, toFs, toLevel, dir == Direction.DOWN);
		} else if (toFs.isEmpty() || canReplace(toFs, to, dir)) {
			setFluid(to, toState, toFs, amount, dir == Direction.DOWN);
		} else {
			Ocwo.LOGGER.warn("Impossible " + to);
		}
	}

	private boolean slopeFlow(Vec2F slope) {
		Direction dir = FluidUtils.getDirection(slope.xf(), slope.yf());
		if (dir == null) return false;
		int delta = canFlow(dir);
		if (delta > 0) {
			delta /= 2;
			if (delta == 0) delta = 1;
			flow(dir, delta);
			return true;
		} else {
			return false;
		}
	}

	/**
	 * OCWO pressure trigger: queues the body of fluid for a pressure step (see PressureSearch).
	 * <ul>
	 *   <li>Non-full block without fluid on top, next to a full block of the same fluid.</li>
	 *   <li>Full block with a free cell on top or on a side.</li>
	 * </ul>
	 */
	private void pressure() {
		if (level < MAX_LEVEL) {
			if (haveFluidOnTop(pos, state, shape)) return;
			for (Direction dir : PRESSURE_DIRS) {
				BlockPos n = pos.relative(dir);
				if (isStillFull(getFluidState(n)) && havePath(pos, state, shape, dir, true)) {
					fluidTicks.enqueuePressure(n, fluid);
					return;
				}
			}
			return;
		}
		fluidTicks.enqueuePressure(pos, fluid);
	}

	private static final Direction[] PRESSURE_DIRS = {
			Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
	};

	private void equalize() {
		if (level < 2 && !havePath(pos, state, shape, Direction.DOWN, false)) return;
		fluidTicks.scheduleEqualization(pos, fluid, world);
	}


	private int canFlow(Direction dir) {
		return Math.min(canFlow(pos, state, shape, dir, dir == Direction.DOWN), this.level);
	}

	private int canFlow(BlockPos from, BlockState fromState, VoxelShape fromShape, Direction dir, boolean fromAbove) {
		BlockPos to = from.relative(dir);
		BlockState toState = getBlockState(to);
		if (!fromAbove && FluidUtils.checkForWLLimit(toState, this.fluid)) {
			return 0;
		}
		VoxelShape toShape = toState.getCollisionShape(world, to);
		if (FluidUtils.isPathBlocked(fromState, fromShape, toState, toShape, dir)) {
			if (!toState.canBeReplaced(fluid)) {
				return 0;
			}
			if (FluidUtils.isPathBlocked(fromState, fromShape, null, Shapes.empty(), dir)) {
				return 0;
			}
		}
		FluidState toFs = getFluidState(to);
		if (isThis(toFs)) {
			int level2 = toFs.getAmount();
			if (fromAbove) {
				return MAX_LEVEL - level2;
			}
			return this.level - level2;
		}
		if (toFs.isEmpty() || canReplace(toFs, to, dir)) return MAX_LEVEL;
		return 0;
	}
}
