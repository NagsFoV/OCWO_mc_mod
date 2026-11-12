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

	private OcwoProfiler() {
	}

	public static void start(ServerLevel level) {
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
