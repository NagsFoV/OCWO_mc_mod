package io.github.nagsfov.ocwo.gametest;

import io.github.nagsfov.ocwo.OcwoBootConfig;
import io.github.nagsfov.ocwo.util.OcwoProfiler;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * OCWO: cost of the world generation (Valentin, 19/11/2026: spikes when large areas are generated, without changing
 * anything). Run alone, on real terrain (normal world, seed 0): gradlew :fabric:runGameTestGeneration (see build.gradle),
 * once with the fluids part of OCWO and once without it, from a new world each time.
 * <p>
 * A band of chunks moves forward like a fast flight: a new row of {@link #ROW} chunks is asked for every
 * {@link #ROW_EVERY} ticks (64 chunks per second), without waiting for it (like the chunks around a player); the rows
 * more than {@link #KEEP} behind are let go (unloaded and saved). Reports the time of the main thread per tick (the whole
 * server) and the time of our code, during the flight then while the last rows stay loaded.
 */
public class GenerationGameTests {

	private static final int ROW = 32;
	private static final int ROWS = 40;
	private static final int ROW_EVERY = 10;
	private static final int KEEP = 12;
	private static final int SETTLE = 200;
	/** Far from the tests and from the spawn chunks. */
	private static final int CX0 = 256;
	private static final int CZ0 = -ROW / 2;

	@GameTest(maxTicks = 30_000)
	public void flight(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		MinecraftServer server = level.getServer();
		String label = OcwoBootConfig.INSTANCE.isFluidPhysicsEnabled() ? "OCWO on" : "OCWO off (fluids part disabled)";
		boolean[] full = new boolean[ROWS];
		boolean[] released = new boolean[ROWS];
		int[] asked = {0};
		int[] phase = {0}; // 0 flight, 1 settle, 2 unload, 3 done
		long[] phaseStart = {h.getTick(), 0};
		long[] wallStart = {System.nanoTime()};
		List<Long> times = new ArrayList<>();
		OcwoProfiler.start(level);
		h.onEachTick(() -> {
			if (phase[0] == 3) return;
			long t = h.getTick() - phaseStart[0];
			// main thread time of the previous tick (the current one is still running)
			long[] tickTimes = server.getTickTimesNanos();
			times.add(tickTimes[Math.floorMod(server.getTickCount() - 1, tickTimes.length)]);
			if (phase[0] == 0) {
				if (t % ROW_EVERY == 0 && asked[0] < ROWS) {
					for (int z = 0; z < ROW; z++) force(level, asked[0], z, true);
					asked[0]++;
				}
				boolean all = asked[0] == ROWS;
				for (int r = 0; r < asked[0]; r++) {
					if (!full[r]) full[r] = rowFull(level, r);
					all &= full[r];
					if (full[r] && !released[r] && r < asked[0] - KEEP) {
						for (int z = 0; z < ROW; z++) force(level, r, z, false);
						released[r] = true;
					}
				}
				if (all) {
					report(label + ", flight of " + ROWS * ROW + " chunks", times, t, System.nanoTime() - wallStart[0]);
					next(h, phase, phaseStart, wallStart, times, level);
				}
			} else if (phase[0] == 1 && t >= SETTLE) {
				report(label + ", last " + KEEP * ROW + " chunks kept loaded", times, t, System.nanoTime() - wallStart[0]);
				for (int r = 0; r < ROWS; r++)
					if (!released[r]) {
						for (int z = 0; z < ROW; z++) force(level, r, z, false);
						released[r] = true;
					}
				next(h, phase, phaseStart, wallStart, times, level);
			} else if (phase[0] == 2 && t >= SETTLE) {
				report(label + ", unloading", times, t, System.nanoTime() - wallStart[0]);
				OcwoProfiler.stop();
				phase[0] = 3;
				h.succeed();
			}
		});
	}

	private static void next(GameTestHelper h, int[] phase, long[] phaseStart, long[] wallStart, List<Long> times, ServerLevel level) {
		phase[0]++;
		phaseStart[0] = h.getTick();
		wallStart[0] = System.nanoTime();
		times.clear();
		OcwoProfiler.start(level);
	}

	private static void force(ServerLevel level, int row, int z, boolean forced) {
		// the chunk source directly: ServerLevel.setChunkForced would wait for the chunk on the main thread
		level.getChunkSource().updateChunkForced(new ChunkPos(CX0 + row, CZ0 + z), forced);
	}

	private static boolean rowFull(ServerLevel level, int row) {
		for (int z = 0; z < ROW; z++)
			if (level.getChunkSource().getChunkNow(CX0 + row, CZ0 + z) == null) return false;
		return true;
	}

	private static void report(String what, List<Long> times, long ticks, long wallNanos) {
		long sum = 0, max = 0;
		int over50 = 0, over100 = 0;
		List<Long> sorted = new ArrayList<>(times);
		sorted.sort(null);
		for (long v : times) {
			sum += v;
			max = Math.max(max, v);
			if (v > 50_000_000L) over50++;
			if (v > 100_000_000L) over100++;
		}
		long p95 = sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * 0.95)));
		StringBuilder worst = new StringBuilder();
		for (int i = sorted.size() - 1; i >= Math.max(0, sorted.size() - 5); i--)
			worst.append(String.format(Locale.ROOT, "%.1f ", sorted.get(i) / 1e6));
		System.out.println(String.format(Locale.ROOT,
				"[OCWO-TEST] generation %s: %d ticks, %.1f s; main thread %.1f ms in all, %.2f ms/tick average, p95 %.1f ms, worst %.1f ms (%s), ticks over 50 ms: %d, over 100 ms: %d",
				what, ticks, wallNanos / 1e9, sum / 1e6, times.isEmpty() ? 0 : sum / 1e6 / times.size(), p95 / 1e6, max / 1e6,
				worst.toString().trim(), over50, over100));
		System.out.println(String.format(Locale.ROOT,
				"[OCWO-TEST] generation %s: our fluid tick %.3f ms/tick average, %.2f ms worst (moisture %.3f ms/tick); our code %.1f ms in all (its parts: tick = our fluid tick, hook = called by the game, in = inside them): %s",
				what, OcwoProfiler.averageMillis(), OcwoProfiler.maxMillis(), OcwoProfiler.moistureAverageMillis(),
				OcwoProfiler.sectionsMillis(), OcwoProfiler.sectionsReport()));
	}
}
