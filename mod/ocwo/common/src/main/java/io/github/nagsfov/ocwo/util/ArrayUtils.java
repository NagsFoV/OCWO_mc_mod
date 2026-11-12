package io.github.nagsfov.ocwo.util;

import java.lang.reflect.Array;

/** The few array helpers PhysEx used from skdslib. */
public final class ArrayUtils {

	public static final long[] EMPTY_LONG = {};

	private ArrayUtils() {
	}

	@SuppressWarnings("unchecked")
	public static <T> T[] createGenericArray(Class<?> type, int size) {
		return (T[]) Array.newInstance(type, size);
	}

	public static <T> T getRandom(T[] array) {
		if (array.length == 0) return null;
		return array[FastMath.RANDOM.nextInt(array.length)];
	}
}
