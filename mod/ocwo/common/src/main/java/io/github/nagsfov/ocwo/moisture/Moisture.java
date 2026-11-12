package io.github.nagsfov.ocwo.moisture;

/**
 * OCWO block moisture: constants and stages. See 03-humidite-et-etats-des-blocs.md.
 * <p>
 * Moisture is stored in integer units so that water is conserved exactly:
 * {@link #UNITS_PER_LEVEL} units = 1 water level (1/8 block).
 */
public final class Moisture {

	public static final int UNITS_PER_LEVEL = 16;

	private Moisture() {
	}

	/** Visible stages of a block, from its fill ratio (moisture / capacity). */
	public enum Stage {
		/** under 25 % */
		SEC("sec"),
		/** 25 % to 50 % */
		HUMIDE("humide"),
		/** 50 % to 100 % */
		MOUILLE("mouille"),
		/** full */
		SATURE("sature");

		public final String id;

		Stage(String id) {
			this.id = id;
		}

		public static Stage of(int units, int capacity) {
			if (capacity <= 0 || units <= 0) return SEC;
			if (units >= capacity) return SATURE;
			if (units * 2 >= capacity) return MOUILLE;
			if (units * 4 >= capacity) return HUMIDE;
			return SEC;
		}

		public static Stage byId(String id, Stage fallback) {
			if (id == null) return fallback;
			for (Stage s : values()) if (s.id.equals(id)) return s;
			return fallback;
		}
	}
}
