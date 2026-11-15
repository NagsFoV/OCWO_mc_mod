package io.github.nagsfov.ocwo.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

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

	/**
	 * Vessels next to the 32x32x8 lake while it drains (Valentin, 15/11/2026): a 5x5x20 vessel, full, joined by a
	 * 1x1 tunnel to an empty one, beside the pool. Reports the water code time and when the vessels balanced.
	 */
	@GameTest(environment = "ocwo-gametest:perf_mid_vessel", maxTicks = 9_000, padding = 32)
	public void midLakeVessel(GameTestHelper h) {
		DrainScenario.Pool pool = midLake();
		DrainScenario.Run run = new DrainScenario.Run(h, pool, "midLake with vessels (pressure on)", MAX_DRAIN_TICKS, true);
		run.failWhenSlow = false;
		int x0 = 2, z0 = pool.size() + 6, w = 5, height = 20, len = 5;
		net.minecraft.server.level.ServerLevel level = h.getLevel();
		int xR = x0 + w + len + 1;
		for (int x = x0 - 1; x <= xR + w; x++)
			for (int z = 0; z <= w + 1; z++)
				for (int y = 0; y <= height + 1; y++) {
					boolean inZ = z >= 1 && z <= w;
					boolean left = x >= x0 && x < x0 + w && inZ && y >= 1;
					boolean right = x >= xR && x < xR + w && inZ && y >= 1;
					boolean tunnel = x >= x0 + w && x < xR && z == 3 && y == 1;
					net.minecraft.world.level.block.state.BlockState st = Blocks.OBSIDIAN.defaultBlockState();
					if (left) st = y <= height - 2 ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
					else if (right || tunnel) st = Blocks.AIR.defaultBlockState();
					level.setBlock(h.absolutePos(new BlockPos(x, y, z0 + z)), st, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
				}
		level.scheduleTick(h.absolutePos(new BlockPos(x0 + w - 1, 1, z0 + 3)), net.minecraft.world.level.material.Fluids.WATER, 1);
		long start = h.getTick();
		boolean[] done = {false};
		h.onEachTick(() -> {
			if (done[0] || (h.getTick() - start) % 10 != 0) return;
			int l = top(h, x0, z0, w, height), r = top(h, xR, z0, w, height);
			if (l >= 0 && r >= 0 && Math.abs(l - r) <= 1) {
				done[0] = true;
				System.out.println("[OCWO-TEST] midLake with vessels: vessels balanced at tick " + (h.getTick() - start));
			}
		});
		run.start();
	}

	private static int top(GameTestHelper h, int x0, int z0, int w, int height) {
		int best = -1;
		for (int x = x0; x < x0 + w; x++)
			for (int z = 1; z <= w; z++)
				for (int y = height; y >= 1; y--) {
					var fs = h.getLevel().getFluidState(h.absolutePos(new BlockPos(x, y, z0 + z)));
					if (!fs.isEmpty()) { best = Math.max(best, y * 8 + fs.getAmount()); break; }
				}
		return best;
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

	/**
	 * Large wet area: a 48x48 pool, 2 blocks deep, over a floor of dirt and cobblestone (alternating rows), over an
	 * obsidian shell. Measures the time of our water code (moisture included) for 2 000 ticks: the floor soaks up,
	 * dirt turns to mud, cobblestone saturates. Fails above 10 ms per tick on average or 50 ms on the worst tick.
	 */
	@GameTest(environment = "ocwo-gametest:perf_moisture", maxTicks = 2_600, padding = 32)
	public void wetArea(GameTestHelper h) {
		int n = 48;
		net.minecraft.server.level.ServerLevel level = h.getLevel();
		int quiet = net.minecraft.world.level.block.Block.UPDATE_CLIENTS;
		for (int x = 0; x <= n + 1; x++)
			for (int z = 0; z <= n + 1; z++) {
				boolean wall = x == 0 || z == 0 || x == n + 1 || z == n + 1;
				for (int y = 0; y <= 4; y++) {
					net.minecraft.world.level.block.state.BlockState s;
					if (y == 0 || wall) s = net.minecraft.world.level.block.Blocks.OBSIDIAN.defaultBlockState();
					else if (y == 1) s = (z % 2 == 0 ? net.minecraft.world.level.block.Blocks.DIRT : net.minecraft.world.level.block.Blocks.COBBLESTONE).defaultBlockState();
					else if (y <= 3) s = net.minecraft.world.level.block.Blocks.WATER.defaultBlockState();
					else s = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
					level.setBlock(h.absolutePos(new net.minecraft.core.BlockPos(x, y, z)), s, quiet);
				}
			}
		// wake the pool up so that the floor starts soaking (as a player pouring water would)
		h.runAfterDelay(5, () -> {
			for (int x = 1; x <= n; x++)
				for (int z = 1; z <= n; z++)
					io.github.nagsfov.ocwo.fluids.CustomFluidTicks.get(level).moisture().activate(h.absolutePos(new net.minecraft.core.BlockPos(x, 1, z)));
			io.github.nagsfov.ocwo.util.OcwoProfiler.start(level);
		});
		long[] initial = {-1};
		h.runAfterDelay(6, () -> initial[0] = WaterCount.units(h, 0, 0, 0, n + 1, 4, n + 1));
		h.runAfterDelay(2_006, () -> {
			io.github.nagsfov.ocwo.util.OcwoProfiler.stop();
			long now = WaterCount.units(h, 0, 0, 0, n + 1, 4, n + 1);
			int mud = 0;
			long moisture = 0;
			for (int x = 1; x <= n; x++)
				for (int z = 1; z <= n; z++) {
					net.minecraft.core.BlockPos p = new net.minecraft.core.BlockPos(x, 1, z);
					if (h.getBlockState(p).is(net.minecraft.world.level.block.Blocks.MUD)) mud++;
					moisture += WaterCount.moisture(h, p);
				}
			double avg = io.github.nagsfov.ocwo.util.OcwoProfiler.averageMillis();
			double worst = io.github.nagsfov.ocwo.util.OcwoProfiler.maxMillis();
			System.out.println(String.format("[OCWO-TEST] wetArea over %d ticks: water code %.2f ms/tick average (moisture %.2f), %.2f ms worst tick; floor moisture %d units, %d mud blocks",
					io.github.nagsfov.ocwo.util.OcwoProfiler.ticks(), avg, io.github.nagsfov.ocwo.util.OcwoProfiler.moistureAverageMillis(), worst, moisture, mud));
			if (now != initial[0]) {
				h.fail("Water not conserved: " + initial[0] + " units, now " + now);
				return;
			}
			if (avg > 10 || worst > 50) {
				h.fail(String.format("Water code too slow: %.2f ms per tick on average, %.2f ms worst tick", avg, worst));
				return;
			}
			h.succeed();
		});
	}
}
