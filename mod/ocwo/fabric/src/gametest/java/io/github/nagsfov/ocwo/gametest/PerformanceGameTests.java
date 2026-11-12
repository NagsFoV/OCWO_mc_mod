package io.github.nagsfov.ocwo.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * OCWO performance scenarios: a lake drained into a basement, measuring the time spent in our water code.
 * Not part of the regular run: launch them with gradlew :fabric:runGameTestPerf.
 * Each scenario has its own test environment, so it runs alone in its batch (no shared budget, clean timing).
 * The "no pressure" variants set ocwo:pressure_search_limit to 0: they measure the original PhysEx engine alone.
 */
public class PerformanceGameTests {

	private static final int MAX_DRAIN_TICKS = 6_000;

	private static DrainScenario.Pool bigLake() {
		// 64x64x16 lake (65 536 blocks of water), 16 holes, 64x64x18 basement with pillars
		return new DrainScenario.Pool(64, 18, 16, 4, 16);
	}

	private static DrainScenario.Pool midLake() {
		// 32x32x8 lake (8 192 blocks of water), 4 holes, 32x32x10 basement with pillars
		return new DrainScenario.Pool(32, 10, 8, 4, 16);
	}

	/** Extreme case: numbers are reported, the 10 / 50 ms targets are not enforced. */
	@GameTest(environment = "ocwo-gametest:perf_big", maxTicks = 9_000, padding = 32)
	public void bigLake(GameTestHelper h) {
		DrainScenario.Run run = new DrainScenario.Run(h, bigLake(), "bigLake (pressure on)", MAX_DRAIN_TICKS, true);
		run.failWhenSlow = false;
		run.start();
	}

	@GameTest(environment = "ocwo-gametest:perf_big_no_pressure", maxTicks = 9_000, padding = 32)
	public void bigLakeNoPressure(GameTestHelper h) {
		DrainScenario.Run run = new DrainScenario.Run(h, bigLake(), "bigLake (pressure off, PhysEx only)", MAX_DRAIN_TICKS, true);
		run.failWhenSlow = false;
		run.start();
	}

	/** Realistic case: must stay under 10 ms per tick on average and 50 ms on the worst tick. */
	@GameTest(environment = "ocwo-gametest:perf_mid", maxTicks = 9_000, padding = 32)
	public void midLake(GameTestHelper h) {
		new DrainScenario.Run(h, midLake(), "midLake (pressure on)", MAX_DRAIN_TICKS, true).start();
	}

	@GameTest(environment = "ocwo-gametest:perf_mid_no_pressure", maxTicks = 9_000, padding = 32)
	public void midLakeNoPressure(GameTestHelper h) {
		DrainScenario.Run run = new DrainScenario.Run(h, midLake(), "midLake (pressure off, PhysEx only)", MAX_DRAIN_TICKS, true);
		run.failWhenSlow = false;
		run.start();
	}
}
