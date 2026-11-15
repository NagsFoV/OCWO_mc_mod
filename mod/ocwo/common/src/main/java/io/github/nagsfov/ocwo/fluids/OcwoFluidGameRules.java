package io.github.nagsfov.ocwo.fluids;

import io.github.nagsfov.ocwo.OcwoBootConfig;
import io.github.nagsfov.ocwo.platform.Services;
import net.minecraft.world.level.gamerules.GameRule;

public class OcwoFluidGameRules {

	public static final GameRule<Integer> TASK_LIMIT = enabled()
			? Services.PLATFORM.registerIntRule("task_limit", 10_000, 500, Integer.MAX_VALUE) : null;

	public static final GameRule<Boolean> DRIPSTONE_FILL_CAULDRON = enabled()
			? Services.PLATFORM.registerBooleanRule("dripstone_fill_cauldron", false) : null;

	public static final GameRule<Integer> FLUID_DISPLACEMENT_LIMIT = enabled()
			? Services.PLATFORM.registerIntRule("fluid_displacement_limit", 8, 0, 32) : null;

	public static final GameRule<Integer> WATER_HOT_EVAPORATION = enabled()
			? Services.PLATFORM.registerIntRule("water_hot_fluid_evaporation", 2, 0, 8) : null;

	public static final GameRule<Double> FARMLAND_WATER_INTAKE = enabled()
			? Services.PLATFORM.registerDoubleRule("farmland_water_intake", 0.01, -1D, 1D) : null;

	/** true = AREA (bucket also takes from the connected blocks around), false = COLUMN (PhysEx behaviour). */
	public static final GameRule<Boolean> BUCKET_AREA_PICKUP = enabled()
			? Services.PLATFORM.registerBooleanRule("bucket_area_pickup", true) : null;

	/** Max number of columns of fluid explored by one pressure search (0 = pressure disabled). See 06-pression.md. */
	public static final GameRule<Integer> PRESSURE_SEARCH_LIMIT = enabled()
			? Services.PLATFORM.registerIntRule("pressure_search_limit", 1024, 0, 65_536) : null;

	/**
	 * Pressure operations per tick: a search costs 1 per block of water it explores, each level moved costs 1. Fixed quota: the tick waits for it
	 * (see 06-pression.md). Multiplied by {@link #SIMULATION_SCALE}.
	 */
	public static final GameRule<Integer> PRESSURE_OPERATIONS_PER_TICK = enabled()
			? Services.PLATFORM.registerIntRule("pressure_operations_per_tick", 2048, 1, 10_000_000) : null;

	/** Max speed-up of pressure flow (levels per tick and per block of opening); 1 = no speed-up. */
	public static final GameRule<Integer> PRESSURE_FLOW_MAX = enabled()
			? Services.PLATFORM.registerIntRule("pressure_flow_max", 4, 1, 16) : null;

	/** Percentage applied to every quota of the water simulation (flow tasks, pressure, moisture). */
	public static final GameRule<Integer> SIMULATION_SCALE = enabled()
			? Services.PLATFORM.registerIntRule("water_simulation_scale", 100, 1, 1000) : null;

	/** Optional time cap of the water simulation per tick, in ms (0 = off: the tick always finishes its quotas). */
	public static final GameRule<Integer> WATER_TIME_BUDGET_MS = enabled()
			? Services.PLATFORM.registerIntRule("water_time_budget_ms", 0, 0, 1000) : null;

	private static boolean enabled() {
		return OcwoBootConfig.INSTANCE.isFluidPhysicsEnabled();
	}

	public static void init() {
	}
}
