package io.github.nagsfov.ocwo.fluids;

import io.github.nagsfov.ocwo.OcwoBootConfig;
import io.github.nagsfov.ocwo.platform.Services;
import net.minecraft.world.level.gamerules.GameRule;

public class OcwoFluidGameRules {

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

	/** Block moisture simulation (03-humidite-et-etats-des-blocs.md). */
	public static final GameRule<Boolean> MOISTURE_SIMULATION = enabled()
			? Services.PLATFORM.registerBooleanRule("moisture_simulation", true) : null;

	/** Moisture random ticks: blocks drawn per section with moisture, every tick (like randomTickSpeed). */
	public static final GameRule<Integer> MOISTURE_RANDOM_TICKS = enabled()
			? Services.PLATFORM.registerIntRule("moisture_random_ticks", 3, 0, 4096) : null;

	/** Evaporation of moisture transfers, percent in a temperate biome (more if hot and dry): goes to the clouds. */
	public static final GameRule<Double> MOISTURE_EVAPORATION = enabled()
			? Services.PLATFORM.registerDoubleRule("moisture_evaporation", 2.0, 0D, 100D) : null;

	/** Smallest body of natural water at rest that pays out of its own surface (abstract reservoir); 0 = none. */
	public static final GameRule<Integer> RESERVOIR_MIN_BLOCKS = enabled()
			? Services.PLATFORM.registerIntRule("reservoir_min_blocks", 8, 0, 1_000_000) : null;

	/** TEST ONLY, NOT CONSERVATIVE: vanilla rain adds moisture to the top blocks (no clouds yet). */
	public static final GameRule<Boolean> RAIN_MOISTURE_TEST = enabled()
			? Services.PLATFORM.registerBooleanRule("rain_moisture_test", false) : null;

	/** Water at rest: the water of the world generation is not simulated until something disturbs it. */
	public static final GameRule<Boolean> WATER_REST = enabled()
			? Services.PLATFORM.registerBooleanRule("water_rest", true) : null;

	/** Hydraulic pressure (communicating vessels, tunnels that fill up...). See 06-pression.md. */
	public static final GameRule<Boolean> WATER_PRESSURE = enabled()
			? Services.PLATFORM.registerBooleanRule("water_pressure", true) : null;

	/**
	 * Water simulation distance in chunks around players (-1 = the server's simulation distance). Beyond it, water
	 * is frozen, nothing lost, like chunks that do not tick. This sets the amount of work of the water simulation.
	 */
	public static final GameRule<Integer> WATER_SIMULATION_DISTANCE = enabled()
			? Services.PLATFORM.registerIntRule("water_simulation_distance", -1, -1, 64) : null;

	/** Max speed-up of pressure flow (levels per tick and per block of opening); 1 = no speed-up. */
	public static final GameRule<Integer> PRESSURE_FLOW_MAX = enabled()
			? Services.PLATFORM.registerIntRule("pressure_flow_max", 4, 1, 16) : null;

	private static boolean enabled() {
		return OcwoBootConfig.INSTANCE.isFluidPhysicsEnabled();
	}

	public static void init() {
	}
}
