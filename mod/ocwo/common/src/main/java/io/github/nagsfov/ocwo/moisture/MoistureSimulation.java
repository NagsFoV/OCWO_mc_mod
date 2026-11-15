package io.github.nagsfov.ocwo.moisture;

import io.github.nagsfov.ocwo.fluids.FluidUtils;
import io.github.nagsfov.ocwo.fluids.OcwoFluidGameRules;
import io.github.nagsfov.ocwo.platform.Services;
import io.github.nagsfov.ocwo.util.OcwoProfiler;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * OCWO block moisture simulation for one level (see 03-humidite-et-etats-des-blocs.md).
 * <p>
 * Slow: runs every {@link #PERIOD} ticks, on every "active" block within the water simulation distance (complete:
 * the tick waits for it; blocks beyond the distance are frozen). One step of a block:
 * <ol>
 *   <li>absorption: takes one water level from a neighbouring water block (if not full);</li>
 *   <li>transmission: shares moisture with porous neighbours, by fill ratio and permeability, biased downwards;</li>
 *   <li>seepage: a saturated block that still has a supply gives one level of water to a free cell below or
 *   beside it (never above), unless it retains its water or changes state at that stage;</li>
 *   <li>state change: e.g. dirt to mud when wet, mud back to dirt when dry (hysteresis); moisture is kept.</li>
 * </ol>
 * Water is conserved exactly: 1 water level = {@link Moisture#UNITS_PER_LEVEL} units of moisture.
 */
public final class MoistureSimulation {

	public static final int PERIOD = 10;
	private static final int SAMPLE_PERIOD = 20;
	private static final int SAMPLES_PER_PLAYER = 32;
	private static final int SAMPLE_RADIUS = 24;
	private static final int U = Moisture.UNITS_PER_LEVEL;
	private static final Direction[] SEEP_DIRS = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

	private final ServerLevel level;
	private final LongLinkedOpenHashSet active = new LongLinkedOpenHashSet();
	/** true while the simulation itself changes a block (state change), so that the block-change hook ignores it */
	private boolean internalChange;

	public MoistureSimulation(ServerLevel level) {
		this.level = level;
	}

	private boolean enabled() {
		return OcwoFluidGameRules.MOISTURE_SIMULATION != null && level.getGameRules().get(OcwoFluidGameRules.MOISTURE_SIMULATION);
	}

	public boolean isActive(BlockPos pos) {
		return active.contains(pos.asLong());
	}

	public int activeCount() {
		return active.size();
	}

	// ------------------------------------------------------------------ storage

	public int units(BlockPos pos) {
		LevelChunk chunk = level.getChunkAt(pos);
		MoistureLayer layer = Services.PLATFORM.getMoistureLayer(chunk);
		return layer == null ? 0 : layer.get(chunk, pos);
	}

	public void setUnits(BlockPos pos, int units) {
		LevelChunk chunk = level.getChunkAt(pos);
		MoistureLayer layer = units == 0 ? Services.PLATFORM.getMoistureLayer(chunk) : Services.PLATFORM.getOrCreateMoistureLayer(chunk);
		if (layer == null) return;
		layer.set(chunk, pos, units);
		chunk.markUnsaved();
	}

	// ------------------------------------------------------------------ activation

	public void activate(BlockPos pos) {
		active.add(pos.asLong());
	}

	/** Activates the moisture-holding blocks among this position and its neighbours. */
	public void activateAround(BlockPos pos) {
		if (!enabled()) return;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		if (holds(pos)) active.add(pos.asLong());
		for (Direction d : Direction.values()) {
			m.setWithOffset(pos, d);
			if (level.isLoaded(m) && holds(m)) active.add(m.asLong());
		}
	}

	private boolean holds(BlockPos pos) {
		MoistureProps p = MoistureData.get(level.getBlockState(pos));
		return p != null && p.holdsMoisture();
	}

	/**
	 * Called after a block of the level changed (outside the simulation's own state changes).
	 * Symmetric accounting of the fixed water of blocks made of water that hold moisture (mud, wet sponge):
	 * <ul>
	 *   <li>removed (mined, replaced): the item carries min(moisture, fixed water); the rest stays in the cell
	 *   and is released as water like for any block;</li>
	 *   <li>placed (by a player, a command, a piston…): the fixed water of the item is added to the cell.</li>
	 * </ul>
	 * Blocks made of water that hold no moisture (ice, snow) are left alone for now.
	 */
	public void onBlockChanged(BlockPos pos, BlockState oldState, BlockState newState) {
		if (internalChange || oldState.getBlock() == newState.getBlock()) return;
		MoistureProps oldProps = MoistureData.get(oldState);
		MoistureProps newProps = MoistureData.get(newState);
		if (oldProps == null && newProps == null) return;
		if (oldProps != null && oldProps.holdsMoisture() && oldProps.fixedWater() > 0) {
			int h = units(pos);
			int carried = Math.min(h, oldProps.fixedWater());
			if (carried > 0) setUnits(pos, h - carried);
		}
		if (newProps != null && newProps.holdsMoisture() && newProps.fixedWater() > 0) {
			setUnits(pos, units(pos) + newProps.fixedWater());
		}
		active.add(pos.asLong());
		activateAround(pos);
	}

	/**
	 * A block that loses water when water is taken from its cell would be destroyed (kelp, seagrass: always full,
	 * no fluid layer) or broken (bubble columns): the moisture system never takes water from those cells.
	 */
	private static boolean canTakeWaterFrom(BlockState state) {
		if (state.is(net.minecraft.world.level.block.Blocks.BUBBLE_COLUMN)) return false;
		return !(state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlockContainer)
				|| state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED);
	}

	// ------------------------------------------------------------------ tick

	public void tick() {
		if (!enabled()) {
			active.clear();
			return;
		}
		long time = level.getGameTime();
		if (time % SAMPLE_PERIOD == 0) sample();
		if (time % PERIOD != 0 || active.isEmpty()) return;
		long start = System.nanoTime();
		io.github.nagsfov.ocwo.fluids.WaterBodies range = io.github.nagsfov.ocwo.fluids.CustomFluidTicks.get(level).bodies();
		// every active block, each step (the blocks it activates are for the next step)
		int count = active.size();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int i = 0; i < count && !active.isEmpty(); i++) {
			long p = active.removeFirstLong();
			pos.set(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p));
			if (!level.isLoaded(pos) || !range.inRange(pos)) {
				active.add(p); // frozen beyond the water simulation distance: it waits, nothing is lost
				continue;
			}
			if (step(pos.immutable())) active.add(p);
		}
		OcwoProfiler.addMoistureNanos(level, System.nanoTime() - start);
	}

	/** Finds blocks that should be simulated near players (still water against porous blocks, rain test). */
	private void sample() {
		RandomSource random = io.github.nagsfov.ocwo.util.FastMath.RANDOM;
		boolean rain = OcwoFluidGameRules.RAIN_MOISTURE_TEST != null && level.getGameRules().get(OcwoFluidGameRules.RAIN_MOISTURE_TEST)
				&& level.isRaining();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (var player : level.players()) {
			BlockPos c = player.blockPosition();
			for (int i = 0; i < SAMPLES_PER_PLAYER; i++) {
				BlockPos p = c.offset(random.nextInt(SAMPLE_RADIUS * 2 + 1) - SAMPLE_RADIUS,
						random.nextInt(SAMPLE_RADIUS + 1) - SAMPLE_RADIUS / 2,
						random.nextInt(SAMPLE_RADIUS * 2 + 1) - SAMPLE_RADIUS);
				if (!level.isLoaded(p)) continue;
				MoistureProps props = MoistureData.get(level.getBlockState(p));
				if (props == null || !props.holdsMoisture()) continue;
				if (units(p) > 0) {
					active.add(p.asLong());
					continue;
				}
				for (Direction d : Direction.values()) {
					m.setWithOffset(p, d);
					if (level.isLoaded(m) && isWater(level.getFluidState(m))) {
						active.add(p.asLong());
						break;
					}
				}
			}
			if (rain) rainTest(c, random);
		}
	}

	/** NOT conservative (test only, game rule ocwo:rain_moisture_test): rain adds moisture to the top block. */
	private void rainTest(BlockPos center, RandomSource random) {
		for (int i = 0; i < 8; i++) {
			int x = center.getX() + random.nextInt(SAMPLE_RADIUS * 2 + 1) - SAMPLE_RADIUS;
			int z = center.getZ() + random.nextInt(SAMPLE_RADIUS * 2 + 1) - SAMPLE_RADIUS;
			BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(x, 0, z)).below();
			if (!level.isLoaded(top) || !level.isRainingAt(top.above())) continue;
			MoistureProps props = MoistureData.get(level.getBlockState(top));
			if (props == null || !props.holdsMoisture()) continue;
			int h = units(top);
			if (h < props.capacity()) {
				setUnits(top, Math.min(props.capacity(), h + U));
				active.add(top.asLong());
			}
		}
	}

	// ------------------------------------------------------------------ one block

	private static boolean isWater(FluidState fs) {
		return !fs.isEmpty() && fs.getType().isSame(Fluids.WATER);
	}

	/** @return true to keep the block active */
	private boolean step(BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		MoistureProps props = MoistureData.get(state);
		int h = units(pos);
		if (props == null || !props.holdsMoisture()) {
			// The block can no longer hold moisture (mined, replaced): release it as water here or next to it.
			return h > 0 && release(pos, h);
		}
		RandomSource random = io.github.nagsfov.ocwo.util.FastMath.RANDOM;
		int cap = props.capacity();
		if (props.fixedWater() > 0 && h < props.fixedWater()) {
			// A block made of water with less moisture than its fixed water can only come from world generation
			// (mangrove mud): its generation water is initialised once (the only creation of water allowed).
			h = props.fixedWater();
			setUnits(pos, h);
		}
		int start = h;
		boolean supply = false;
		boolean waterAround = false;

		// 1. absorption from neighbouring water (one level per step)
		for (Direction d : Direction.values()) {
			BlockPos n = pos.relative(d);
			if (!level.isLoaded(n)) continue;
			FluidState fs = level.getFluidState(n);
			if (!isWater(fs)) continue;
			waterAround = true;
			if (d != Direction.DOWN) supply = true;
			int perm = props.permeability(state, d);
			if (perm <= 0 || h + U > cap || random.nextInt(10) >= perm) continue;
			if (!canTakeWaterFrom(level.getBlockState(n))) continue;
			if (fs.getType() instanceof FlowingFluid ff && fs.getAmount() > 0) {
				FluidUtils.setFluid(level, n, ff, fs.getAmount() - 1, false);
				h += U;
				break;
			}
		}

		// 2. transmission to porous neighbours
		for (Direction d : Direction.values()) {
			if (h <= 0) break;
			BlockPos n = pos.relative(d);
			if (!level.isLoaded(n)) continue;
			BlockState ns = level.getBlockState(n);
			MoistureProps np = MoistureData.get(ns);
			if (np == null || !np.holdsMoisture()) continue;
			int h2 = units(n);
			int c2 = np.capacity();
			if (d != Direction.DOWN && h2 >= c2) supply = true;
			int perm = Math.min(props.permeability(state, d), np.permeability(ns, d.getOpposite()));
			if (perm <= 0 || h2 >= c2) continue;
			// fill ratios compared without division; moving downwards is favoured (gravity)
			long num = (long) h * c2 - (long) h2 * cap;
			if (d == Direction.DOWN) num += (long) cap * c2 / 4;
			else if (d == Direction.UP) num -= (long) cap * c2 / 4;
			if (num <= 0) continue;
			long x = num / (cap + c2) * perm / 10;
			if (x == 0 && random.nextInt(10) < perm) x = 1;
			x = Math.min(x, Math.min(h, c2 - h2));
			if (x <= 0) continue;
			h -= (int) x;
			setUnits(n, h2 + (int) x);
			active.add(n.asLong());
		}

		// 3. seepage: saturated (or over capacity after a state change) and still supplied
		Moisture.Stage stage = Moisture.Stage.of(h, cap);
		boolean changesWhenSaturated = props.transformTo() != null && props.transformStage().ordinal() <= Moisture.Stage.SATURE.ordinal()
				&& stage.ordinal() >= props.transformStage().ordinal();
		if (h > cap || (h >= cap && supply && !props.retains() && !changesWhenSaturated)) {
			int perm = Math.max(1, props.permeability());
			if (h > cap || random.nextInt(10) < perm) {
				for (Direction d : SEEP_DIRS) {
					if (h < U) break;
					BlockPos n = pos.relative(d);
					if (!level.isLoaded(n)) continue;
					if (props.permeability(state, d) <= 0 && h <= cap) continue;
					if (putWater(n)) {
						h -= U;
						if (h <= cap) break;
					}
				}
			}
		}

		if (h != start) setUnits(pos, h);

		// 4. state change (moisture is kept)
		stage = Moisture.Stage.of(h, cap);
		Block to = null;
		if (props.transformTo() != null && stage.ordinal() >= props.transformStage().ordinal()) to = props.transformTo();
		else if (props.revertTo() != null && stage.ordinal() < props.revertStage().ordinal()) to = props.revertTo();
		if (to != null) {
			internalChange = true;
			try {
				level.setBlock(pos, to.defaultBlockState(), Block.UPDATE_ALL);
			} finally {
				internalChange = false;
			}
			return true;
		}
		return h != start || waterAround || (h > 0 && hasPorousNeighbourToFill(pos, state, props, h));
	}

	private boolean hasPorousNeighbourToFill(BlockPos pos, BlockState state, MoistureProps props, int h) {
		for (Direction d : Direction.values()) {
			BlockPos n = pos.relative(d);
			if (!level.isLoaded(n)) continue;
			BlockState ns = level.getBlockState(n);
			MoistureProps np = MoistureData.get(ns);
			if (np == null || !np.holdsMoisture()) continue;
			if (Math.min(props.permeability(state, d), np.permeability(ns, d.getOpposite())) <= 0) continue;
			int h2 = units(n);
			long num = (long) h * np.capacity() - (long) h2 * props.capacity();
			if (d == Direction.DOWN) num += (long) props.capacity() * np.capacity() / 4;
			if (num > (long) (props.capacity() + np.capacity()) && h2 < np.capacity()) return true;
		}
		return false;
	}

	/** Adds one water level in this cell if it can take it (air, replaceable, or water that is not full). */
	private boolean putWater(BlockPos n) {
		FluidState fs = level.getFluidState(n);
		FlowingFluid water = Fluids.WATER;
		if (isWater(fs)) {
			if (fs.getAmount() >= FluidUtils.MAX_LEVEL || PressureFalling.is(fs)) return false;
			FluidUtils.setFluid(level, n, water, fs.getAmount() + 1, false);
			return true;
		}
		if (!fs.isEmpty()) return false;
		BlockState ns = level.getBlockState(n);
		if (!ns.isAir() && !ns.canBeReplaced(water)) return false;
		FluidUtils.setFluid(level, n, water, 1, false);
		return true;
	}

	/** Releases moisture of a block that no longer holds any. Keeps what it cannot place (released later). */
	private boolean release(BlockPos pos, int h) {
		int left = h;
		if (left >= U && putWaterLevels(pos, left / U)) left = h - (h / U) * U;
		for (Direction d : SEEP_DIRS) {
			if (left < U) break;
			BlockPos n = pos.relative(d);
			if (level.isLoaded(n) && putWater(n)) left -= U;
		}
		// Less than one level (or no room): stays in the cell until it can go (no water is ever lost).
		setUnits(pos, left);
		return left >= U && left != h;
	}

	private boolean putWaterLevels(BlockPos pos, int levels) {
		FluidState fs = level.getFluidState(pos);
		int existing = isWater(fs) ? fs.getAmount() : 0;
		if (!fs.isEmpty() && !isWater(fs)) return false;
		BlockState s = level.getBlockState(pos);
		if (existing == 0 && !s.isAir() && !s.canBeReplaced(Fluids.WATER)) return false;
		if (existing + levels > FluidUtils.MAX_LEVEL) return false;
		FluidUtils.setFluid(level, pos, Fluids.WATER, existing + levels, false);
		return true;
	}

	/** Small helper: falling water is left to the classic flow. */
	private static final class PressureFalling {
		static boolean is(FluidState fs) {
			return fs.hasProperty(FlowingFluid.FALLING) && fs.getValue(FlowingFluid.FALLING);
		}
	}
}
