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

	private static boolean enabled() {
		return OcwoBootConfig.INSTANCE.isFluidPhysicsEnabled();
	}

	public static void init() {
	}
}
