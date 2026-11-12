package io.github.nagsfov.ocwo.gametest;

import io.github.nagsfov.ocwo.util.OcwoProfiler;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.List;

/**
 * OCWO: shared scenario for the game tests: a lake drained through holes in its floor into a lower basement.
 * <ul>
 *   <li>Performance (time spent in our water code per tick) and conservation: {@code PerformanceGameTests}, each
 *   scenario in its own batch so nothing else runs at the same time.</li>
 *   <li>Both tests: once the lake is empty, the sky light must be correct (server side) in the whole drained
 *   volume. See 05-problemes-connus.md, section 1.</li>
 *   <li>Fences under water (water kept in the fluid layer): {@code PressureLightGameTests}.</li>
 * </ul>
 */
public final class DrainScenario {

	private DrainScenario() {
	}

	/** Fail if our water code takes more than this on average per tick. */
	private static final double MAX_AVERAGE_MS = 10.0;
	/** Fail if our water code takes more than this on its worst tick. */
	private static final double MAX_WORST_MS = 50.0;
	/** Ticks left to the light engine once the lake is empty. */
	private static final int LIGHT_SETTLE_TICKS = 60;

	/** Geometry of one scenario, in test-relative coordinates. */
	record Pool(int size, int basementHeight, int lakeHeight, int pillarStep, int holeStep) {
		int floorY() {
			return basementHeight + 1;
		}

		int lakeMinY() {
			return floorY() + 1;
		}

		int lakeMaxY() {
			return floorY() + lakeHeight;
		}
	}

	// ------------------------------------------------------------------ building

	private static void put(ServerLevel level, GameTestHelper h, int x, int y, int z, BlockState state, int flags) {
		level.setBlock(h.absolutePos(new BlockPos(x, y, z)), state, flags);
	}

	static void forceChunks(GameTestHelper h, int size, boolean force) {
		BlockPos a = h.absolutePos(new BlockPos(0, 0, 0));
		BlockPos b = h.absolutePos(new BlockPos(size + 1, 0, size + 1));
		for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++)
			for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++)
				h.getLevel().setChunkForced(cx, cz, force);
	}

	/** Stone box, basement with pillars, a floor, then a lake open to the sky, full of water. */
	static void build(GameTestHelper h, Pool p) {
		ServerLevel level = h.getLevel();
		int quiet = Block.UPDATE_CLIENTS; // no neighbour updates while building
		BlockState stone = Blocks.STONE.defaultBlockState();
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState water = Blocks.WATER.defaultBlockState();
		int n = p.size();
		for (int x = 0; x <= n + 1; x++)
			for (int z = 0; z <= n + 1; z++) {
				boolean wall = x == 0 || z == 0 || x == n + 1 || z == n + 1;
				boolean pillar = !wall && (x - 1) % p.pillarStep() == 0 && (z - 1) % p.pillarStep() == 0;
				for (int y = 0; y <= p.lakeMaxY() + 1; y++) {
					BlockState s;
					if (y == 0 || wall || y == p.floorY()) s = stone;
					else if (y <= p.basementHeight()) s = pillar ? stone : air;
					else if (y <= p.lakeMaxY()) s = water;
					else s = air;
					put(level, h, x, y, z, s, quiet);
				}
			}
	}

	/** Opens the holes of the floor between the lake and the basement (with neighbour updates: water starts). */
	static void openHoles(GameTestHelper h, Pool p) {
		int n = p.size();
		for (int x = p.holeStep() / 2; x <= n; x += p.holeStep())
			for (int z = p.holeStep() / 2; z <= n; z += p.holeStep())
				put(h.getLevel(), h, x, p.floorY(), z, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
	}

	private static int level(GameTestHelper h, int x, int y, int z) {
		FluidState fs = h.getLevel().getFluidState(h.absolutePos(new BlockPos(x, y, z)));
		return fs.getType().isSame(Fluids.WATER) ? fs.getAmount() : 0;
	}

	static long total(GameTestHelper h, Pool p, int y0, int y1) {
		long sum = 0;
		for (int x = 1; x <= p.size(); x++)
			for (int z = 1; z <= p.size(); z++)
				for (int y = y0; y <= y1; y++)
					sum += level(h, x, y, z);
		return sum;
	}

	private static long fenceWater(GameTestHelper h, Pool p) {
		long sum = 0;
		for (int x = 1; x <= p.size(); x++)
			for (int z = 1; z <= p.size(); z++)
				for (int y = p.lakeMinY(); y <= p.lakeMaxY(); y++)
					if (h.getLevel().getBlockState(h.absolutePos(new BlockPos(x, y, z))).is(Blocks.OAK_FENCE))
						sum += level(h, x, y, z);
		return sum;
	}

	// ------------------------------------------------------------------ light

	/**
	 * Server-side sky light in the drained lake: every non-solid cell of the lake volume has open sky above it
	 * (the lake is open to the sky), so its sky light must be 15.
	 *
	 * @return description of the wrong cells, or null if all correct
	 */
	private static String checkSkyLight(GameTestHelper h, Pool p) {
		ServerLevel level = h.getLevel();
		int wrong = 0;
		int checked = 0;
		List<String> examples = new ArrayList<>();
		for (int x = 1; x <= p.size(); x++)
			for (int z = 1; z <= p.size(); z++)
				for (int y = p.lakeMinY(); y <= p.lakeMaxY(); y++) {
					BlockPos abs = h.absolutePos(new BlockPos(x, y, z));
					BlockState s = level.getBlockState(abs);
					if (s.canOcclude() || !level.getFluidState(abs).isEmpty()) continue;
					checked++;
					int sky = level.getBrightness(LightLayer.SKY, abs);
					if (sky != 15) {
						wrong++;
						if (examples.size() < 5) examples.add("(" + x + "," + y + "," + z + ")=" + sky);
					}
				}
		System.out.println("[OCWO-TEST] sky light check: " + checked + " cells, " + wrong + " wrong " + examples);
		return wrong == 0 ? null : wrong + "/" + checked + " drained cells with wrong sky light, e.g. " + examples;
	}

	// ------------------------------------------------------------------ scenario runner

	static final class Run {
		final GameTestHelper h;
		final Pool p;
		final String name;
		final int maxDrainTicks;
		final boolean profile;
		/** false: report the numbers only (extreme or reference scenarios). */
		boolean failWhenSlow = true;
		long initial;
		long startTick = -1;
		long drainedTick = -1;
		boolean finished;
		long lastLakeWater = -1;
		long lakeStableSince = -1;
		boolean stable;
		long serverSum;
		long serverMax;
		long serverTicks;

		Run(GameTestHelper h, Pool p, String name, int maxDrainTicks, boolean profile) {
			this.h = h;
			this.p = p;
			this.name = name;
			this.maxDrainTicks = maxDrainTicks;
			this.profile = profile;
		}

		void start() {
			forceChunks(h, p.size(), true);
			build(h, p);
			h.runAfterDelay(5, () -> {
				initial = total(h, p, 1, p.lakeMaxY() + 1);
				System.out.println("[OCWO-TEST] " + name + ": " + p.size() + "x" + p.size() + "x" + p.lakeHeight()
						+ " lake, " + initial + " levels of water");
				if (profile) OcwoProfiler.start(h.getLevel());
				startTick = h.getTick();
				openHoles(h, p);
			});
			h.onEachTick(this::tick);
		}

		void tick() {
			if (startTick < 0 || finished) return;
			long t = h.getTick() - startTick;
			if (profile && t > 0) {
				MinecraftServer server = h.getLevel().getServer();
				long[] times = server.getTickTimesNanos();
				long last = times[(server.getTickCount() - 1 + times.length) % times.length];
				serverSum += last;
				serverTicks++;
				if (last > serverMax) serverMax = last;
			}
			if (drainedTick < 0 && t % 20 == 0 && t > 0) {
				long now = total(h, p, 1, p.lakeMaxY() + 1);
				if (now != initial) {
					finish();
					h.fail("Water not conserved: " + initial + " levels at start, " + now + " at tick " + t);
					return;
				}
				long lake = total(h, p, p.lakeMinY(), p.lakeMaxY());
				boolean drained = lake == 0;
				// Stable: the water left in the lake (puddles) has not changed for 200 ticks.
				if (lake != lastLakeWater) {
					lastLakeWater = lake;
					lakeStableSince = t;
				}
				stable = drained || t - lakeStableSince >= 200;
				if (stable || t >= maxDrainTicks) {
					drainedTick = t;
					System.out.println("[OCWO-TEST] " + name + ": " + (drained ? "lake empty" : stable ? "lake stable (puddles left)" : "stopped, lake still draining")
							+ " after " + t + " ticks; water left in the lake: " + total(h, p, p.lakeMinY(), p.lakeMaxY())
							+ " levels, in the basement: " + total(h, p, 1, p.basementHeight()) + " levels"
							+ (drained ? "" : ", in fences: " + fenceWater(h, p)));
					if (profile) report();
				}
			}
			if (drainedTick >= 0 && t >= drainedTick + LIGHT_SETTLE_TICKS) {
				String light = checkSkyLight(h, p);
				finish();
				if (profile && failWhenSlow) {
					double avg = OcwoProfiler.averageMillis();
					double worst = OcwoProfiler.maxMillis();
					if (avg > MAX_AVERAGE_MS || worst > MAX_WORST_MS) {
						h.fail(String.format("Water code too slow: %.2f ms per tick on average, %.2f ms worst tick (limits %.0f / %.0f)",
								avg, worst, MAX_AVERAGE_MS, MAX_WORST_MS));
						return;
					}
				}
				// The light is only judged once the water has stopped moving (else the light engine may lag a tick).
				if (light != null && stable) {
					h.fail(light);
					return;
				}
				if (light != null) {
					System.out.println("[OCWO-TEST] " + name + ": light not judged, the water was still moving");
				}
				h.succeed();
			}
		}

		void report() {
			System.out.println(String.format(
					"[OCWO-TEST] %s performance over %d ticks: water code %.2f ms/tick average (pressure %.2f), %.2f ms worst tick; "
							+ "server tick %.2f ms average, %.2f ms worst; pressure steps %d (%d moved a level)",
					name, OcwoProfiler.ticks(), OcwoProfiler.averageMillis(), OcwoProfiler.pressureAverageMillis(), OcwoProfiler.maxMillis(),
					serverTicks == 0 ? 0 : serverSum / 1e6 / serverTicks, serverMax / 1e6,
					OcwoProfiler.pressureSteps(), OcwoProfiler.pressureMoves()));
			OcwoProfiler.stop();
		}

		void finish() {
			finished = true;
			if (profile) OcwoProfiler.stop();
			forceChunks(h, p.size(), false);
		}
	}

}
