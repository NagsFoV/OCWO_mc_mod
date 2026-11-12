package io.github.nagsfov.ocwo.util;

import java.util.Random;

/** The few math helpers PhysEx used from skdslib. */
public final class FastMath {

	public static final Random RANDOM = new Random();

	private FastMath() {
	}

	public static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	public static int ceil(float value) {
		return (int) Math.ceil(value);
	}
}
