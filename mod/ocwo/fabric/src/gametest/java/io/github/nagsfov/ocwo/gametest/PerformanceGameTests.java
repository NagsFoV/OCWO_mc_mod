package io.github.nagsfov.ocwo.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/**
 * OCWO performance scenarios: a lake drained into a basement, measuring the time spent in our water code.
 * Not part of the regular run: launch them with gradlew :fabric:runGameTestPerf.
 * Each scenario has its own test environment, so it runs alone in its batch (no shared budget, clean timing).
 * The "no pressure" variants set ocwo:water_pressure to false: they measure the original PhysEx engine alone.
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

	/**
	 * A sea of 160 x 160 x 12 blocks (25 600 columns) with a player in it who keeps placing and breaking blocks under
	 * water near them: the body of water is built once (complete, no limit) then only updated around the changes.
	 * Reports the time of our water code; the 10 / 50 ms targets are not enforced.
	 */
	@GameTest(environment = "ocwo-gametest:perf_sea", maxTicks = 3_000, padding = 32)
	public void seaActivity(GameTestHelper h) {
		int n = 160, depth = 12;
		net.minecraft.server.level.ServerLevel level = h.getLevel();
		DrainScenario.forceChunks(h, n + 2, true);
		for (int x = 0; x <= n + 1; x++)
			for (int z = 0; z <= n + 1; z++)
				for (int y = 0; y <= depth + 1; y++) {
					boolean inside = x >= 1 && x <= n && z >= 1 && z <= n && y >= 1;
					net.minecraft.world.level.block.state.BlockState st = !inside ? Blocks.OBSIDIAN.defaultBlockState()
							: y <= depth ? Blocks.WATER.defaultBlockState() : Blocks.AIR.defaultBlockState();
					level.setBlock(h.absolutePos(new BlockPos(x, y, z)), st, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
				}
		net.minecraft.server.level.ServerPlayer player = h.makeMockServerPlayerInLevel();
		BlockPos c = h.absolutePos(new BlockPos(n / 2, depth + 2, n / 2));
		player.teleportTo(c.getX() + 0.5, c.getY(), c.getZ() + 0.5);
		long start = h.getTick();
		long[] firstTick = {-1};
		int[] k = {0};
		h.onEachTick(() -> {
			long t = h.getTick() - start;
			if (t == 5) {
				io.github.nagsfov.ocwo.util.OcwoProfiler.start(level);
				io.github.nagsfov.ocwo.fluids.CustomFluidTicks.get(level).enqueuePressure(h.absolutePos(new BlockPos(n / 2, 1, n / 2)),
						net.minecraft.world.level.material.Fluids.WATER);
			}
			if (t == 7) firstTick[0] = (long) (io.github.nagsfov.ocwo.util.OcwoProfiler.maxMillis() * 1000);
			if (t > 10 && t % 4 == 0 && t < 2000) {
				// place or break a block under water near the player
				int dx = (k[0] * 7) % 17 - 8, dz = (k[0] * 11) % 17 - 8;
				BlockPos p = new BlockPos(n / 2 + dx, 6, n / 2 + dz);
				boolean solid = !level.getBlockState(h.absolutePos(p)).getFluidState().isEmpty();
				level.setBlock(h.absolutePos(p), solid ? Blocks.STONE.defaultBlockState() : Blocks.WATER.defaultBlockState(),
						net.minecraft.world.level.block.Block.UPDATE_ALL);
				k[0]++;
			}
			if (t == 2100) {
				var b = io.github.nagsfov.ocwo.fluids.CustomFluidTicks.get(level).bodies();
				System.out.println(String.format(java.util.Locale.ROOT,
						"[OCWO-TEST] seaActivity performance over %d ticks: water code %.2f ms/tick average (pressure %.2f), %.2f ms worst tick (first build of the sea: %.1f ms); bodies %d, columns %d, levels moved %d",
						io.github.nagsfov.ocwo.util.OcwoProfiler.ticks(), io.github.nagsfov.ocwo.util.OcwoProfiler.averageMillis(),
						io.github.nagsfov.ocwo.util.OcwoProfiler.pressureAverageMillis(), io.github.nagsfov.ocwo.util.OcwoProfiler.maxMillis(),
						firstTick[0] / 1000.0, b.bodyCount(), b.columnCount(), io.github.nagsfov.ocwo.util.OcwoProfiler.pressureMoves()));
				io.github.nagsfov.ocwo.util.OcwoProfiler.stop();
				DrainScenario.forceChunks(h, n + 2, false);
				h.succeed();
			}
		});
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
		// the water is simulated in ticking chunks only (water simulation distance): keep them ticking
		DrainScenario.forceChunks(h, n + 1, true);
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
			DrainScenario.forceChunks(h, n + 1, false);
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

	// ------------------------------------------------------------------ abstract reservoirs (stabilisation, step 4)

	/**
	 * A natural lake of 48 x 48 x 4 (9 216 blocks, more than one reservoir search looks at), written like the world
	 * generation, and an empty basin of 16 x 16 x 2 behind a wall; the player opens the wall (3 blocks wide). Reports
	 * the water code time, the awake water, the reservoir searches and how evenly the lake surface went down.
	 * Variants: reservoirs from 8 blocks (default), from 64 blocks, and none (the lake flows for real).
	 */
	private static void reservoirTrench(GameTestHelper h, String name) {
		net.minecraft.server.level.ServerLevel level = h.getLevel();
		int n = 48, bx0 = n + 3, bn = 16, ticks = 1_200;
		DrainScenario.forceChunks(h, bx0 + bn + 1, true);
		net.minecraft.world.level.block.state.BlockState obs = Blocks.OBSIDIAN.defaultBlockState(), air = Blocks.AIR.defaultBlockState(),
				water = Blocks.WATER.defaultBlockState();
		for (int x = 0; x <= bx0 + bn; x++)
			for (int z = 0; z <= n + 1; z++)
				for (int y = 0; y <= 6; y++) {
					boolean basin = x >= bx0 && x < bx0 + bn && z >= 1 && z <= bn && y >= 1 && y <= 2;
					level.setBlock(h.absolutePos(new BlockPos(x, y, z)), basin ? air : obs, 2);
				}
		for (int x = 1; x <= n; x++)
			for (int z = 1; z <= n; z++)
				for (int y = 1; y <= 6; y++) {
					BlockPos p = h.absolutePos(new BlockPos(x, y, z));
					level.getChunkAt(p).setBlockState(p, y <= 4 ? water : air, 0); // like the generation
				}
		long[] start = {0};
		int[] maxAwake = {0};
		long[] s0 = new long[3];
		h.runAfterDelay(5, () -> {
			start[0] = waterLevels(h, bx0 + bn, n + 1);
			s0[0] = io.github.nagsfov.ocwo.fluids.WaterReservoirs.searches;
			s0[1] = io.github.nagsfov.ocwo.fluids.WaterReservoirs.payments;
			s0[2] = io.github.nagsfov.ocwo.fluids.WaterReservoirs.visited;
			// the player opens the wall between the lake and the basin, 3 blocks wide
			for (int z = 7; z <= 9; z++) level.setBlock(h.absolutePos(new BlockPos(n + 1, 1, z)), air, net.minecraft.world.level.block.Block.UPDATE_ALL);
			for (int z = 7; z <= 9; z++) level.setBlock(h.absolutePos(new BlockPos(n + 2, 1, z)), air, net.minecraft.world.level.block.Block.UPDATE_ALL);
			io.github.nagsfov.ocwo.util.OcwoProfiler.start(level);
		});
		long[] last = new long[3];
		h.onEachTick(() -> {
			if (h.getTick() % 20 == 0) maxAwake[0] = Math.max(maxAwake[0], io.github.nagsfov.ocwo.fluids.WaterRest.awakeAround(level, h.absolutePos(new BlockPos(n / 2, 1, n / 2)), 4));
			long se = io.github.nagsfov.ocwo.fluids.WaterReservoirs.searches, pa = io.github.nagsfov.ocwo.fluids.WaterReservoirs.payments,
					mv = io.github.nagsfov.ocwo.util.OcwoProfiler.pressureMoves();
			if (h.getTick() > 6 && io.github.nagsfov.ocwo.util.OcwoProfiler.lastMillis() > 50)
				System.out.println(String.format(java.util.Locale.ROOT, "[OCWO-PERF]   slow tick %d: %.1f ms, %d searches, %d payments, %d pressure moves; so far %d small, %d no surface, %d plan fails; last search at %s (relative %s)",
						h.getTick(), io.github.nagsfov.ocwo.util.OcwoProfiler.lastMillis(), se - last[0], pa - last[1], mv - last[2],
						io.github.nagsfov.ocwo.fluids.WaterReservoirs.smallBodies, io.github.nagsfov.ocwo.fluids.WaterReservoirs.noSurface,
						io.github.nagsfov.ocwo.fluids.WaterReservoirs.planFails, BlockPos.of(io.github.nagsfov.ocwo.fluids.WaterReservoirs.lastSearchPos).toShortString(),
						BlockPos.of(io.github.nagsfov.ocwo.fluids.WaterReservoirs.lastSearchPos).subtract(h.absolutePos(BlockPos.ZERO)).toShortString()));
			last[0] = se;
			last[1] = pa;
			last[2] = mv;
		});
		h.runAfterDelay(5 + ticks, () -> {
			io.github.nagsfov.ocwo.util.OcwoProfiler.stop();
			long reserve = 0;
			java.util.Set<Long> zones = new java.util.HashSet<>();
			for (int x : new int[]{0, bx0 + bn})
				for (int z : new int[]{0, n + 1}) {
					long zone = io.github.nagsfov.ocwo.fluids.WaterReserves.zone(h.absolutePos(new BlockPos(x, 0, z)));
					if (zones.add(zone)) reserve += io.github.nagsfov.ocwo.fluids.WaterReserves.get(level).reserveOf(zone);
				}
			int basin = 0;
			for (int x = bx0; x < bx0 + bn; x++)
				for (int z = 1; z <= bn; z++)
					for (int y = 1; y <= 2; y++) basin += WaterCount.liquidLevels(h, new BlockPos(x, y, z));
			// lake surface: top layer near the opening (x > 40, z < 16) and far from it (x < 8, z > 40)
			int nearLo = 99, nearHi = -1, farLo = 99, farHi = -1, lakeTop = 0;
			for (int x = 1; x <= n; x++)
				for (int z = 1; z <= n; z++) {
					int top = 0;
					for (int y = 4; y >= 1 && top == 0; y--) {
						int l = WaterCount.liquidLevels(h, new BlockPos(x, y, z));
						if (l > 0) top = (y - 1) * 8 + l;
					}
					lakeTop += top;
					if (x > 40 && z < 16) { nearLo = Math.min(nearLo, top); nearHi = Math.max(nearHi, top); }
					if (x < 8 && z > 40) { farLo = Math.min(farLo, top); farHi = Math.max(farHi, top); }
				}
			int awake = io.github.nagsfov.ocwo.fluids.WaterRest.awakeAround(level, h.absolutePos(new BlockPos(n / 2, 1, n / 2)), 4);
			long now = waterLevels(h, bx0 + bn, n + 1);
			long searches = io.github.nagsfov.ocwo.fluids.WaterReservoirs.searches - s0[0];
			long payments = io.github.nagsfov.ocwo.fluids.WaterReservoirs.payments - s0[1];
			long visited = io.github.nagsfov.ocwo.fluids.WaterReservoirs.visited - s0[2];
			System.out.println(String.format(java.util.Locale.ROOT,
					"[OCWO-PERF] %s: %d ticks, water code %.2f ms/tick avg, %.1f ms worst; basin %d levels; awake water %d now, %d at most;"
							+ " reservoir: %d payments, %d searches (%.0f blocks each); lake surface height near %d..%d, far %d..%d (start 32);"
							+ " water %d -> %d levels + reserve %d units",
					name, io.github.nagsfov.ocwo.util.OcwoProfiler.ticks(), io.github.nagsfov.ocwo.util.OcwoProfiler.averageMillis(),
					io.github.nagsfov.ocwo.util.OcwoProfiler.maxMillis(), basin, awake, maxAwake[0], payments, searches,
					searches == 0 ? 0.0 : visited / (double) searches, nearLo, nearHi, farLo, farHi, start[0], now, reserve));
			DrainScenario.forceChunks(h, bx0 + bn + 1, false);
			h.succeed();
		});
	}

	private static long waterLevels(GameTestHelper h, int x1, int z1) {
		long sum = 0;
		for (int x = 0; x <= x1; x++)
			for (int z = 0; z <= z1; z++)
				for (int y = 0; y <= 6; y++) sum += WaterCount.liquidLevels(h, new BlockPos(x, y, z));
		return sum;
	}

	@GameTest(environment = "ocwo-gametest:perf_reservoir_8", maxTicks = 1_400, padding = 32)
	public void reservoirTrench8(GameTestHelper h) {
		reservoirTrench(h, "lake + trench, reservoirs from 8 blocks");
	}

	@GameTest(environment = "ocwo-gametest:perf_reservoir_64", maxTicks = 1_400, padding = 32)
	public void reservoirTrench64(GameTestHelper h) {
		reservoirTrench(h, "lake + trench, reservoirs from 64 blocks");
	}

	@GameTest(environment = "ocwo-gametest:perf_reservoir_off", maxTicks = 1_400, padding = 32)
	public void reservoirTrenchOff(GameTestHelper h) {
		reservoirTrench(h, "lake + trench, no reservoirs");
	}
}
