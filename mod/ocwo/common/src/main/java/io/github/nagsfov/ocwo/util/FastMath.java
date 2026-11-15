package io.github.nagsfov.ocwo.util;

import net.minecraft.util.RandomSource;

/** The few math helpers PhysEx used from skdslib. */
public final class FastMath {

	/**
	 * Random of the fluid simulation. Seeded from the world seed when the overworld starts ticking (see
	 * CustomFluidTicks), so the same world and the same actions give the same water.
	 */
	public static final RandomSource RANDOM = RandomSource.createThreadSafe();

	private FastMath() {
	}

	public static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	public static int ceil(float value) {
		return (int) Math.ceil(value);
	}
}
