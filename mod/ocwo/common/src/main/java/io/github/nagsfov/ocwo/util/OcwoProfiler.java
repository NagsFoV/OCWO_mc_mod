package io.github.nagsfov.ocwo.util;

import net.minecraft.server.level.ServerLevel;

/**
 * OCWO: lightweight timing of our water code, for performance tests.
 * <p>
 * Disabled by default: when {@link #target} is null, every hook is a single null check (no cost in normal play).
 * A test sets {@link #start(ServerLevel)} on the level to measure; one sample = one tick of that level's fluid code
 * (PhysEx flow, equalization, pressure) plus the fluid displacement done by block placement during that tick.
 */
public final class OcwoProfiler {

	private static volatile ServerLevel target;

	private static long pendingNanos;
	private static long ticks;
	private static long sumNanos;
	private static long maxNanos;
	private static long pressureSteps;
	private static long pressureMoves;
	private static long pressureNanos;
	private static long moistureNanos;

	/** Time of the last tick sample (diagnostics). */
	private static long lastNanos;

	private OcwoProfiler() {
	}

	/** Named parts of our code (time and calls), while profiling (measurements of the world generation). */
	private static final java.util.Map<String, long[]> SECTIONS = new java.util.TreeMap<>();

	/** Adds the time of one call of a named part of our code. */
	public static void addSection(ServerLevel level, String name, long nanos) {
		if (!isProfiling(level)) return;
		long[] s = SECTIONS.computeIfAbsent(name, k -> new long[3]);
		s[0] += nanos;
		s[1]++;
		if (nanos > s[2]) s[2] = nanos;
	}

	/** "name: N ms (calls, worst call)" for each named part, since the profiling started. */
	public static String sectionsReport() {
		StringBuilder sb = new StringBuilder();
		for (var e : SECTIONS.entrySet()) {
			long[] v = e.getValue();
			sb.append(String.format(java.util.Locale.ROOT, "%s: %.1f ms (%d calls, worst %.1f ms); ", e.getKey(), v[0] / 1e6, v[1], v[2] / 1e6));
		}
		return sb.toString();
	}

	/**
	 * Total time of our code, in milliseconds: the parts named "tick: ..." (our fluid tick) and "hook: ..." (our code
	 * called by the game). The other parts are inside them.
	 */
	public static double sectionsMillis() {
		long s = 0;
		for (var e : SECTIONS.entrySet())
			if (e.getKey().startsWith("tick:") || e.getKey().startsWith("hook:")) s += e.getValue()[0];
		return s / 1e6;
	}

	/** Water code time of the last profiled tick, in milliseconds (diagnostics). */
	public static double lastMillis() {
		return lastNanos / 1e6;
	}

	public static void start(ServerLevel level) {
		SECTIONS.clear();
		pendingNanos = 0;
		ticks = 0;
		sumNanos = 0;
		maxNanos = 0;
		pressureSteps = 0;
		pressureMoves = 0;
		pressureNanos = 0;
		moistureNanos = 0;
		target = level;
	}

	public static void stop() {
		target = null;
	}

	public static boolean isProfiling(ServerLevel level) {
		return target != null && target == level;
	}

	/** Adds time spent outside the fluid tick (e.g. displacement) to the current tick sample. */
	public static void addNanos(ServerLevel level, long nanos) {
		if (isProfiling(level)) pendingNanos += nanos;
	}

	/** Closes one tick sample with the time of the fluid tick itself. */
	public static void endTick(ServerLevel level, long tickNanos) {
		if (!isProfiling(level)) return;
		long total = tickNanos + pendingNanos;
		lastNanos = total;
		pendingNanos = 0;
		ticks++;
		sumNanos += total;
		if (total > maxNanos) maxNanos = total;
	}

	public static void countPressureStep(ServerLevel level, boolean moved) {
		if (!isProfiling(level)) return;
		pressureSteps++;
		if (moved) pressureMoves++;
	}

	/** Time spent in the pressure steps (part of the fluid tick). */
	public static void addPressureNanos(ServerLevel level, long nanos) {
		if (isProfiling(level)) pressureNanos += nanos;
	}

	/** Time spent in the moisture simulation (part of the fluid tick). */
	public static void addMoistureNanos(ServerLevel level, long nanos) {
		if (isProfiling(level)) moistureNanos += nanos;
	}

	public static double moistureAverageMillis() {
		return ticks == 0 ? 0 : moistureNanos / 1e6 / ticks;
	}

	public static double pressureAverageMillis() {
		return ticks == 0 ? 0 : pressureNanos / 1e6 / ticks;
	}

	public static long ticks() {
		return ticks;
	}

	public static double averageMillis() {
		return ticks == 0 ? 0 : sumNanos / 1e6 / ticks;
	}

	public static double maxMillis() {
		return maxNanos / 1e6;
	}

	public static long pressureSteps() {
		return pressureSteps;
	}

	public static long pressureMoves() {
		return pressureMoves;
	}
}
