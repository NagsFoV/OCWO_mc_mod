package io.github.nagsfov.ocwo.sponge;

/**
 * The choices of the sponge rules (03-humidite-et-etats-des-blocs.md, "Éponges"), in one place so that they are easy
 * to change. Valentin's rules of 19/11/2026; the values marked "default" were chosen for him and can be corrected.
 */
public final class SpongeRules {

	private SpongeRules() {
	}

	/** Water levels of a sponge (a stretched sponge counts as two sponges, a compressed one too). */
	public static final int SPONGE_LEVELS = 64;

	// ------------------------------------------------------------------ states that hold only under a condition

	/** Default: a stretched sponge with no sticky element left at either end goes back to its cell before stretching. */
	public static final boolean STRETCHED_ALONE_BACK_TO_ORIGIN = true;

	// ------------------------------------------------------------------ pistons

	/** Default: slime and honey stuck to a sponge move with it (the only blocks a sponge can push). */
	public static final boolean GLUED_BLOCKS_MOVE_WITH_SPONGE = true;
	/** Default: a dry sponge pushed against a block is crushed (destroyed, dropped as a dry sponge), it does not block. */
	public static final boolean DRY_SPONGE_CRUSHED = true;
	/**
	 * Chosen alone: a crushed (or pressed) sponge needs room for all the water it gives back (through its 4 side cells,
	 * then further); without it, the piston does not push at all. Nothing ever goes into the piston head any more.
	 */
	public static final boolean CRUSH_NEEDS_ROOM_FOR_ALL = true;
	/**
	 * Valentin (19/11/2026): water is conserved in survival and adventure only. A sponge broken by a player in creative
	 * mode drops nothing (as in vanilla) and its water disappears.
	 */
	public static final boolean CREATIVE_BREAK_DELETES_WATER = true;
	/** Most cells looked at to give water back (from the side cells, breadth first, fixed order). */
	public static final int RELEASE_CELLS = 256;

	// ------------------------------------------------------------------ the sponge as a bucket

	/** Adjacent sponges (plain or stretched, not compressed) share their water evenly (like communicating vessels). */
	public static final boolean EQUALIZE = true;
	/** Most sponges in one group that share their water. */
	public static final int GROUP_MAX = 64;
	/** Drying: only in biomes without precipitation whose base temperature is at least this. */
	public static final float DRYING_MIN_TEMPERATURE = 1.5F;
	/** Drying: only in the open (sky above). */
	public static final boolean DRYING_NEEDS_SKY = true;
	/** Drying: a full sponge dries in about this many ticks (one game day). */
	public static final int DRYING_TICKS_FOR_FULL = 24_000;

	/** Jumping on a full sponge squeezes out 1 level (never under full - 1). */
	public static final int JUMP_LEVELS = 1;
	/** A hard landing (fall over {@link #HARD_FALL} blocks, with particles) squeezes out 4 levels (never under full - 4). */
	public static final int LANDING_LEVELS = 4;
	public static final double HARD_FALL = 3.0;
	/** Smallest fall that counts as a jump (walking down a slab does not squeeze). */
	public static final double JUMP_MIN_FALL = 1.0;
}
