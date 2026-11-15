package io.github.nagsfov.ocwo.fluids;

import io.github.nagsfov.ocwo.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * <b>Water at rest</b> (see 06-pression.md, "Eau au repos"). The world is at rest by default: the fluid of the world
 * generation is not simulated until something disturbs it. While a fluid block is at rest, the flow schedules no
 * task for it, pressure treats it as a wall, moisture does not absorb it and the slow checks around players ignore
 * it. A block change wakes the fluid of the changed block and of its 6 neighbours; moving water changes its
 * neighbours, so the awakening spreads exactly as far as the water moves. Awake positions are saved with the chunk
 * ({@link WakeLayer}); a chunk without them has all its fluid at rest (new chunks and worlds saved before).
 */
public final class WaterRest {

	private static final Direction[] DIRS = Direction.values();

	/** Tests: print who wakes the water at this position. */
	public static volatile BlockPos traceAt;
	/** Diagnostics: print who wakes the water, every N wakes (-Docwo.traceWake=N). */
	private static final int TRACE_EVERY = Integer.getInteger("ocwo.traceWake", 0);
	private static long wakes;

	private WaterRest() {
	}

	public static boolean enabled(ServerLevel level) {
		return OcwoFluidGameRules.WATER_REST == null || level.getGameRules().get(OcwoFluidGameRules.WATER_REST);
	}

	private static LevelChunk chunk(ServerLevel level, BlockPos pos) {
		return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
	}

	/** Is the fluid at pos at rest? (false where there is no fluid, or when the rule is off) */
	public static boolean isResting(ServerLevel level, BlockPos pos) {
		if (!enabled(level)) return false;
		if (level.getFluidState(pos).isEmpty()) return false;
		LevelChunk c = chunk(level, pos);
		if (c == null) return true;
		WakeLayer layer = Services.PLATFORM.getWakeLayer(c);
		return layer == null || !layer.contains(pos);
	}

	/** Wakes the fluid at pos (if any). */
	public static void wake(ServerLevel level, BlockPos pos) {
		if (!enabled(level) || level.getFluidState(pos).isEmpty()) return;
		LevelChunk c = chunk(level, pos);
		if (c == null) return;
		WakeLayer layer = Services.PLATFORM.getWakeLayer(c);
		if (layer != null && layer.contains(pos)) return;
		layer = Services.PLATFORM.getOrCreateWakeLayer(c);
		if (layer.add(pos)) {
			c.markUnsaved();
			if (traceAt != null && traceAt.equals(pos)) new Throwable("[OCWO] water woken at " + pos.toShortString()).printStackTrace(System.out);
			if (TRACE_EVERY > 0 && (++wakes % TRACE_EVERY == 0 || wakes <= 40)) new Throwable("[OCWO] wake #" + wakes + " at " + pos.toShortString()).printStackTrace(System.out);
		}
	}

	private static int suppressed;

	/** While the world generation finishes a chunk, block changes do not wake the water. */
	public static void suppress(boolean on) {
		suppressed = Math.max(0, suppressed + (on ? 1 : -1));
	}

	/**
	 * A block changed: its fluid and the fluid of its 6 neighbours wake up, if the change matters to the water (the
	 * fluid changed, or the block lets water through differently). Not during the end of the world generation.
	 */
	public static void onBlockChanged(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.state.BlockState oldState,
	                                  net.minecraft.world.level.block.state.BlockState newState) {
		if (suppressed > 0 || !enabled(level)) return;
		boolean fluidChanged = !oldState.getFluidState().equals(newState.getFluidState());
		boolean passChanged = oldState.isSolid() != newState.isSolid()
				|| oldState.getCollisionShape(level, pos).isEmpty() != newState.getCollisionShape(level, pos).isEmpty();
		if (fluidChanged || passChanged) wakeAround(level, pos);
	}

	/** A block changed: its fluid and the fluid of its 6 neighbours wake up. */
	public static void wakeAround(ServerLevel level, BlockPos pos) {
		if (suppressed > 0 || !enabled(level)) return;
		wake(level, pos);
		for (Direction d : DIRS) wake(level, pos.relative(d));
	}

	/** Puts every fluid block within {@code radius} chunks of center at rest again; returns the number woken before. */
	public static int restAround(ServerLevel level, BlockPos center, int radius) {
		int cx = center.getX() >> 4, cz = center.getZ() >> 4, n = 0;
		for (int x = cx - radius; x <= cx + radius; x++)
			for (int z = cz - radius; z <= cz + radius; z++) {
				LevelChunk c = level.getChunkSource().getChunkNow(x, z);
				if (c == null) continue;
				WakeLayer layer = Services.PLATFORM.getWakeLayer(c);
				if (layer == null || layer.size() == 0) continue;
				n += layer.size();
				layer.clear();
				c.markUnsaved();
			}
		return n;
	}

	/** Awake fluid blocks within {@code radius} chunks of center (diagnostics). */
	public static int awakeAround(ServerLevel level, BlockPos center, int radius) {
		int cx = center.getX() >> 4, cz = center.getZ() >> 4, n = 0;
		for (int x = cx - radius; x <= cx + radius; x++)
			for (int z = cz - radius; z <= cz + radius; z++) {
				LevelChunk c = level.getChunkSource().getChunkNow(x, z);
				if (c == null) continue;
				WakeLayer layer = Services.PLATFORM.getWakeLayer(c);
				if (layer != null) n += layer.size();
			}
		return n;
	}
}
