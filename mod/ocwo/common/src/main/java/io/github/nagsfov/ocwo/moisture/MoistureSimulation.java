package io.github.nagsfov.ocwo.moisture;

import io.github.nagsfov.ocwo.fluids.FluidUtils;
import io.github.nagsfov.ocwo.fluids.OcwoFluidGameRules;
import io.github.nagsfov.ocwo.platform.Services;
import io.github.nagsfov.ocwo.util.OcwoProfiler;
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
 * <b>Random ticks</b> (like the growth of crops): every tick, {@code ocwo:moisture_random_ticks} blocks drawn at random
 * in every section that has a moisture array, in the chunks that tick within the water simulation distance. A more
 * permeable material moves water more often (each action is drawn with its permeability), so sand always lets water
 * through faster than dirt on average, stone hardly, sealed materials never.
 * <p>
 * <b>Generation moisture</b>: a section without an array is at its equilibrium, derived from depth and biome
 * ({@link #equilibrium}), with nothing stored: saturated under sea level, then dry in dry biomes, damp elsewhere, wet in
 * swamps, never at the stage that would change the block (dirt stays dirt). A section gets its array (filled with
 * that equilibrium) when one of its blocks is disturbed; a block at its equilibrium among blocks at their
 * equilibrium does nothing. One step of a block:
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
	/** Blocks stepped by the random ticks of the last tick (diagnostics). */
	private int lastSteps, steps;
	/** true while the simulation itself changes a block (state change), so that the block-change hook ignores it */
	private boolean internalChange;

	public MoistureSimulation(ServerLevel level) {
		this.level = level;
	}

	private boolean enabled() {
		return OcwoFluidGameRules.MOISTURE_SIMULATION != null && level.getGameRules().get(OcwoFluidGameRules.MOISTURE_SIMULATION);
	}

	/** Still soaking: a block with its own moisture (array) that is not full. Keeps the water next to it awake. */
	public boolean isActive(BlockPos pos) {
		if (!level.isLoaded(pos)) return false;
		LevelChunk chunk = level.getChunkAt(pos);
		MoistureLayer layer = Services.PLATFORM.getMoistureLayer(chunk);
		if (layer == null || !layer.has(chunk, pos)) return false;
		MoistureProps p = MoistureData.get(level.getBlockState(pos));
		return p != null && p.holdsMoisture() && layer.get(chunk, pos) < p.capacity();
	}

	/**
	 * Evaporation of a moisture transfer (03, "Évaporation"): {@code ocwo:moisture_evaporation} percent of it (2 % by
	 * default) in a temperate biome, more where it is hot and dry, less where it is cold. Goes to the clouds.
	 * Returns the whole units that evaporate now.
	 */
	private int evaporate(BlockPos pos, int units) {
		double percent = OcwoFluidGameRules.MOISTURE_EVAPORATION == null ? 2.0 : level.getGameRules().get(OcwoFluidGameRules.MOISTURE_EVAPORATION);
		if (percent <= 0) return 0;
		return io.github.nagsfov.ocwo.fluids.WaterReserves.get(level).evaporate(level, pos, units, percent / 100.0 * evaporationFactor(pos));
	}

	/**
	 * 1 in a temperate biome (base temperature 0.8, with rain or snow), from 0.5 in cold biomes (0.0 and below) up to
	 * 1.75 in hot ones (2.0), doubled where it never rains: 3.5 in deserts.
	 */
	public double evaporationFactor(BlockPos pos) {
		var biome = level.getBiome(pos).value();
		double t = Math.max(0.5, Math.min(1.75, 0.5 + biome.getBaseTemperature() * 0.625));
		return t * (biome.hasPrecipitation() ? 1.0 : 2.0);
	}

	/** Blocks stepped by the random ticks during the last tick. */
	public int activeCount() {
		return lastSteps;
	}

	// ------------------------------------------------------------------ storage

	public int units(BlockPos pos) {
		LevelChunk chunk = level.getChunkAt(pos);
		MoistureLayer layer = Services.PLATFORM.getMoistureLayer(chunk);
		if (layer != null && layer.has(chunk, pos)) return layer.get(chunk, pos);
		return equilibrium(pos, level.getBlockState(pos));
	}

	public void setUnits(BlockPos pos, int units) {
		LevelChunk chunk = level.getChunkAt(pos);
		ensureSection(chunk, pos);
		Services.PLATFORM.getOrCreateMoistureLayer(chunk).set(chunk, pos, units);
		chunk.markUnsaved();
	}

	/** The section of pos gets its array, filled with the equilibrium of its blocks (no water created or lost). */
	private void ensureSection(LevelChunk chunk, BlockPos pos) {
		MoistureLayer layer = Services.PLATFORM.getOrCreateMoistureLayer(chunk);
		if (layer.has(chunk, pos)) return;
		int bx = pos.getX() & ~15, by = pos.getY() & ~15, bz = pos.getZ() & ~15;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		layer.ensure(chunk, pos, k -> {
			m.set(bx | (k & 15), by | (k >> 8), bz | ((k >> 4) & 15));
			return equilibrium(m, chunk.getBlockState(m));
		});
		chunk.markUnsaved();
	}

	private static int stageUnits(Moisture.Stage stage, int cap) {
		return switch (stage) {
			case SEC -> 0;
			case HUMIDE -> (cap * 3) / 8;
			case MOUILLE -> (cap * 3) / 4;
			case SATURE -> cap;
		};
	}

	/**
	 * Generation moisture of a block (nothing stored): saturated under sea level; above, dry in the biomes without
	 * precipitation, wet in swamps, damp elsewhere; never at the stage that would change the block, never under the
	 * stage that would turn it back; at least its fixed water. A pure function of the block, its height and its biome.
	 */
	public int equilibrium(BlockPos pos, BlockState state) {
		MoistureProps props = MoistureData.get(state);
		if (props == null || !props.holdsMoisture()) return 0;
		int cap = props.capacity();
		// sponges (ocean monuments): a dry sponge is dry, a wet one full (not saturated because under sea level)
		if (io.github.nagsfov.ocwo.sponge.OcwoSponges.isAnySponge(state)) return state.is(net.minecraft.world.level.block.Blocks.WET_SPONGE) ? cap : 0;
		Moisture.Stage stage;
		if (pos.getY() < level.getSeaLevel()) {
			stage = Moisture.Stage.SATURE;
		} else {
			var biome = level.getBiome(pos);
			String path = biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
			if (path.contains("swamp")) stage = Moisture.Stage.MOUILLE;
			else if (!biome.value().hasPrecipitation()) stage = Moisture.Stage.SEC;
			else stage = Moisture.Stage.HUMIDE;
		}
		if (props.transformTo() != null && stage.ordinal() >= props.transformStage().ordinal()) {
			stage = Moisture.Stage.values()[Math.max(0, props.transformStage().ordinal() - 1)];
		}
		int units = stageUnits(stage, cap);
		if (props.transformTo() != null) {
			// strictly under the stage that would change the block
			while (units > 0 && Moisture.Stage.of(units, cap).ordinal() >= props.transformStage().ordinal()) units--;
		}
		if (props.revertTo() != null) {
			while (units < cap && Moisture.Stage.of(units, cap).ordinal() < props.revertStage().ordinal()) units++;
		}
		return Math.max(units, Math.min(cap, props.fixedWater()));
	}

	// ------------------------------------------------------------------ activation

	/** The section of pos gets its array: its blocks join the random ticks. */
	public void activate(BlockPos pos) {
		if (level.isLoaded(pos)) ensureSection(level.getChunkAt(pos), pos);
	}

	/** Water moved here: the moisture-holding blocks around it join the random ticks. */
	public void activateAround(BlockPos pos) {
		if (!enabled()) return;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		if (holds(pos)) activate(pos);
		for (Direction d : Direction.values()) {
			m.setWithOffset(pos, d);
			if (level.isLoaded(m) && holds(m)) activate(m.immutable());
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
		// what the old block held (stored, or its generation moisture) stays in the cell
		LevelChunk chunk = level.getChunkAt(pos);
		MoistureLayer layer = Services.PLATFORM.getMoistureLayer(chunk);
		int h = layer != null && layer.has(chunk, pos) ? layer.get(chunk, pos) : equilibrium(pos, oldState);
		if (oldProps != null && oldProps.holdsMoisture() && oldProps.fixedWater() > 0) h -= Math.min(h, oldProps.fixedWater());
		if (newProps != null && newProps.holdsMoisture() && newProps.fixedWater() > 0) h += newProps.fixedWater();
		boolean gone = (newProps == null || !newProps.holdsMoisture()) && !newState.is(net.minecraft.world.level.block.Blocks.MOVING_PISTON);
		if (gone && oldProps != null && oldProps.holdsMoisture() && !io.github.nagsfov.ocwo.sponge.OcwoSponges.isAnySponge(oldState)) {
			// a block that goes away gives its generation moisture back to the ground water of its zone (03, "Registre
			// d'eau par zone"): only what it soaked up on top of it comes out as water (mining damp soil does not flood
			// a tunnel)
			int back = Math.min(h, equilibrium(pos, oldState));
			if (back > 0) {
				io.github.nagsfov.ocwo.fluids.WaterReserves.get(level).giveUnits(level, pos, back);
				h -= back;
			}
		}
		setUnits(pos, h);
		activateAround(pos);
		// a sponge (or double sponge) that went away gives its water back at once (not when a piston moves it)
		if (gone && (io.github.nagsfov.ocwo.sponge.OcwoSponges.isSponge(oldState) || io.github.nagsfov.ocwo.sponge.OcwoSponges.isCompressed(oldState))) {
			io.github.nagsfov.ocwo.sponge.SpongeLogic.onSpongeGone(level, pos, h);
		}
	}

	/** Sets a block without the moisture accounting of block changes (the caller keeps the water balance). */
	public void setBlockQuietly(BlockPos pos, BlockState state, int flags) {
		boolean was = internalChange;
		internalChange = true;
		try {
			level.setBlock(pos, state, flags);
		} finally {
			internalChange = was;
		}
	}

	/**
	 * A block that loses water when water is taken from its cell would be destroyed (kelp, seagrass: always full,
	 * no fluid layer) or broken (bubble columns): the moisture system never takes water from those cells.
	 */
	public static boolean canTakeWaterFrom(BlockState state) {
		if (state.is(net.minecraft.world.level.block.Blocks.BUBBLE_COLUMN)) return false;
		return !(state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlockContainer)
				|| state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED);
	}

	// ------------------------------------------------------------------ tick

	public void tick() {
		lastSteps = steps;
		steps = 0;
		if (!enabled()) return;
		if (level.getGameTime() % SAMPLE_PERIOD == 0) sample();
	}

	/**
	 * Random ticks of one chunk (called for every chunk that ticks, like vanilla random ticks): a fixed number of
	 * blocks drawn at random in each section that has a moisture array. Uses the level's random, like crops.
	 */
	public void randomTickChunk(LevelChunk chunk) {
		if (!enabled()) return;
		MoistureLayer layer = Services.PLATFORM.getMoistureLayer(chunk);
		if (layer == null || layer.sectionCount() == 0) return;
		if (!io.github.nagsfov.ocwo.fluids.CustomFluidTicks.get(level).bodies().inRangeChunk(chunk.getPos().pack())) return;
		int n = OcwoFluidGameRules.MOISTURE_RANDOM_TICKS == null ? 3 : level.getGameRules().get(OcwoFluidGameRules.MOISTURE_RANDOM_TICKS);
		if (n <= 0) return;
		long start = System.nanoTime();
		RandomSource random = level.getRandom();
		int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
		for (int index : layer.sectionIndexes()) {
			int y0 = chunk.getSectionYFromSectionIndex(index) << 4;
			for (int i = 0; i < n; i++) {
				BlockPos pos = new BlockPos(x0 + random.nextInt(16), y0 + random.nextInt(16), z0 + random.nextInt(16));
				steps++;
				step(pos);
			}
		}
		OcwoProfiler.addMoistureNanos(level, System.nanoTime() - start);
	}

	/** Near players: porous blocks next to water that moves (not at rest) join the random ticks. */
	private void sample() {
		RandomSource random = level.getRandom();
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
				for (Direction d : Direction.values()) {
					m.setWithOffset(p, d);
					if (level.isLoaded(m) && isWater(level.getFluidState(m)) && !io.github.nagsfov.ocwo.fluids.WaterRest.isInert(level, m)) {
						activate(p);
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
		if (state.is(net.minecraft.world.level.block.Blocks.MOVING_PISTON)) return false; // carried by a piston (see SpongeLogic)
		MoistureProps props = MoistureData.get(state);
		int h = units(pos);
		if (props == null || !props.holdsMoisture()) {
			// The block can no longer hold moisture (mined, replaced): release it as water here or next to it.
			if (h > 0 && !state.isAir() && state.isCollisionShapeFullBlock(level, pos) && level.getFluidState(pos).isEmpty()) {
				// moisture left in a full block that holds none (stone of a world saved before 19/11/2026, or a block
				// put over wet soil): it goes to the ground water of the zone instead of seeping out of the stone
				io.github.nagsfov.ocwo.fluids.WaterReserves.get(level).giveUnits(level, pos, h);
				setUnits(pos, 0);
				return false;
			}
			return h > 0 && release(pos, h);
		}
		RandomSource random = level.getRandom();
		int cap = props.capacity();
		if (atRest(pos, state, h)) return false;
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
			if (io.github.nagsfov.ocwo.fluids.WaterRest.isInert(level, n)) continue; // water at rest is not absorbed
			waterAround = true;
			if (d != Direction.DOWN) supply = true;
			int perm = props.permeability(state, d);
			if (perm <= 0 || h + U > cap || random.nextInt(10) >= perm) continue;
			if (!canTakeWaterFrom(level.getBlockState(n))) continue;
			if (fs.getType() instanceof FlowingFluid ff && fs.getAmount() > 0) {
				FluidUtils.setFluid(level, n, ff, fs.getAmount() - 1, false);
				h += U - evaporate(pos, U);
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
			setUnits(n, h2 + (int) x - evaporate(pos, (int) x));
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

		if (h != start) {
			setUnits(pos, h);
			if (io.github.nagsfov.ocwo.sponge.OcwoSponges.isAnySponge(state)) io.github.nagsfov.ocwo.sponge.SpongeLogic.updateLook(level, pos);
		}

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

	/**
	 * At rest: the block is at its equilibrium, so are its porous neighbours, and no water that moves touches it:
	 * nothing to do (generation moisture does not drift).
	 */
	private boolean atRest(BlockPos pos, BlockState state, int h) {
		if (h != equilibrium(pos, state)) return false;
		for (Direction d : Direction.values()) {
			BlockPos n = pos.relative(d);
			if (!level.isLoaded(n)) continue;
			FluidState fs = level.getFluidState(n);
			if (isWater(fs) && !io.github.nagsfov.ocwo.fluids.WaterRest.isInert(level, n)) return false;
			BlockState ns = level.getBlockState(n);
			MoistureProps np = MoistureData.get(ns);
			if (np != null && np.holdsMoisture() && units(n) != equilibrium(n, ns)) return false;
		}
		return true;
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
