package io.github.nagsfov.ocwo.fluids;

import io.github.nagsfov.ocwo.platform.Services;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;

/**
 * <b>Water at rest</b> (see 06-pression.md, "Eau au repos"). The world is at rest by default: the fluid of the world
 * generation is not simulated until something disturbs it. While a fluid block is at rest, the flow schedules no
 * task for it, pressure treats it as a wall, moisture does not absorb it and the slow checks around players ignore
 * it. A block change that matters to water wakes the fluid of the changed block and of its 6 neighbours; moving
 * water changes its neighbours, so the awakening spreads as far as the water moves. Awake water that has not moved
 * for {@link #SLEEP_TICKS} ticks, with no flow task due and no pressure body with work next to it, goes back to rest.
 * <p>
 * <b>Reservoir</b>: when the flow (awake water) takes water from natural water (never changed since the world
 * generation) or gives it some, that water keeps its level and the difference goes to the reserve of the zone
 * ({@link WaterReserves}). The reserve starts at 0 and only holds water that was somewhere else before (generation
 * moisture of mined blocks, water poured into natural water). When it cannot pay, a large body of natural water at
 * rest pays out of its own surface ({@link WaterReservoirs}); otherwise the natural water really goes down (and wakes
 * up).
 * <p>
 * Awake positions are saved with the chunk ({@link WakeLayer}); a chunk without them has all its fluid at rest. A
 * chunk of an existing world (players have been there) seen for the first time by this version keeps its moving
 * water (non-full or falling) awake.
 */
public final class WaterRest {

	private static final Direction[] DIRS = Direction.values();
	public static final int SLEEP_TICKS = 100;
	private static final int SLEEP_SWEEP_PERIOD = 20;

	/** Tests: print who wakes the water at this position. */
	public static volatile BlockPos traceAt;
	/** Diagnostics: print who wakes the water, every N wakes (-Docwo.traceWake=N). */
	private static final int TRACE_EVERY = Integer.getInteger("ocwo.traceWake", 0);
	private static long wakes;
	private static int suppressed;
	/** True while the flow (flow and equalization tasks) runs: changes to water at rest go to the reserve. */
	private static boolean simulating;

	private WaterRest() {
	}

	public static boolean enabled(ServerLevel level) {
		return OcwoFluidGameRules.WATER_REST == null || level.getGameRules().get(OcwoFluidGameRules.WATER_REST);
	}

	public static void simulating(boolean on) {
		simulating = on;
	}

	/** While the world generation finishes a chunk, block changes do not wake the water. */
	public static void suppress(boolean on) {
		suppressed = Math.max(0, suppressed + (on ? 1 : -1));
	}

	private static LevelChunk chunk(ServerLevel level, BlockPos pos) {
		return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
	}

	/** The layer of the chunk, after the one-time look at a chunk of an existing world. */
	private static WakeLayer layer(ServerLevel level, LevelChunk c) {
		WakeLayer layer = Services.PLATFORM.getWakeLayer(c);
		if (layer != null && layer.initialized) return layer;
		boolean existing = layer == null && c.getInhabitedTime() > 0;
		layer = Services.PLATFORM.getOrCreateWakeLayer(c);
		layer.initialized = true;
		c.markUnsaved();
		if (!existing) {
			layer.fresh = true; // a new chunk: everything at rest
			return layer;
		}
		// an existing chunk seen for the first time: its moving water (non-full or falling) stays awake
		long t0 = io.github.nagsfov.ocwo.util.OcwoProfiler.isProfiling(level) ? System.nanoTime() : 0L;
		long now = level.getGameTime();
		int minY = c.getMinY();
		LevelChunkSection[] sections = c.getSections();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection s = sections[i];
			if (s.hasOnlyAir() || !s.maybeHas(st -> !st.getFluidState().isEmpty())) continue;
			for (int y = 0; y < 16; y++)
				for (int z = 0; z < 16; z++)
					for (int x = 0; x < 16; x++) {
						FluidState fs = s.getFluidState(x, y, z);
						if (fs.isEmpty()) continue;
						if (fs.getAmount() < FluidUtils.MAX_LEVEL || PressureSearch.isFalling(fs)) {
							layer.add(new BlockPos((c.getPos().x() << 4) | x, minY + (i << 4) + y, (c.getPos().z() << 4) | z), now);
						}
					}
		}
		if (layer.size() > 0) CustomFluidTicks.get(level).awakeChunks().add(c.getPos().pack());
		if (t0 != 0L) io.github.nagsfov.ocwo.util.OcwoProfiler.addSection(level, "rest: first look at an existing chunk", System.nanoTime() - t0);
		return layer;
	}

	/**
	 * A chunk becomes a full chunk (end of its generation, or loaded from the disk): its layer is set now, before the
	 * chunk ticks. A new chunk is born at rest, without any look at its blocks; only a chunk of a world saved before
	 * this system (no layer, already inhabited) gets its one-time look. Done later, at the first question about its
	 * water, a new chunk near a player would already count as inhabited and have its moving water woken.
	 */
	public static void onChunkPromoted(ServerLevel level, LevelChunk c) {
		if (!enabled(level)) return;
		WakeLayer layer = layer(level, c);
		if (layer.fresh) {
			// its first ticks (its layer may have been made a little earlier, by a question from a neighbour chunk)
			layer.fresh = false;
			CustomFluidTicks.get(level).newbornChunks().put(c.getPos().pack(), level.getGameTime());
		}
	}

	/** Ticks after the birth of a chunk during which its block ticks are the end of its generation (see isNewborn). */
	public static final int NEWBORN_TICKS = 20;

	/**
	 * Is pos in a chunk generated less than {@link #NEWBORN_TICKS} ticks ago? Its block ticks then belong to the
	 * world generation (sand and gravel left above a cave fall as soon as the chunk ticks, 2 ticks after its birth):
	 * the changes they make do not wake the water, so that a new chunk is born at rest.
	 */
	public static boolean isNewborn(ServerLevel level, BlockPos pos) {
		var newborn = CustomFluidTicks.get(level).newbornChunks();
		if (newborn.isEmpty()) return false;
		long key = net.minecraft.world.level.ChunkPos.pack(pos);
		long born = newborn.getOrDefault(key, Long.MIN_VALUE);
		if (born == Long.MIN_VALUE) return false;
		if (level.getGameTime() - born < NEWBORN_TICKS) return true;
		newborn.remove(key);
		return false;
	}

	/** Are the block changes of the moment part of the world generation (they wake no water)? */
	public static boolean isSuppressed() {
		return suppressed > 0;
	}

	/** Block ticks scheduled by the world generation: position -> game time they are due (see takeGenerationTick). */
	private static final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap GENERATION_TICKS = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

	/** A block tick was scheduled while the block changes belong to the world generation. */
	public static void onGenerationTickScheduled(BlockPos pos, long due) {
		GENERATION_TICKS.put(pos.asLong(), due);
	}

	/**
	 * Does the block tick about to run at pos belong to the world generation (scheduled by the end of the generation
	 * of a chunk, or by another such tick)? Then its changes do not wake the water: a neighbour chunk born later
	 * makes the sand of an older chunk fall, a pile of sand falls block after block.
	 */
	public static boolean takeGenerationTick(BlockPos pos) {
		if (GENERATION_TICKS.isEmpty()) return false;
		long key = pos.asLong();
		if (!GENERATION_TICKS.containsKey(key)) return false;
		GENERATION_TICKS.remove(key);
		return true;
	}

	/** Is the fluid at pos at rest? (false where there is no fluid, or when the rule is off) */
	public static boolean isResting(ServerLevel level, BlockPos pos) {
		if (!enabled(level)) return false;
		if (level.getFluidState(pos).isEmpty()) return false;
		LevelChunk c = chunk(level, pos);
		if (c == null) return true;
		return !layer(level, c).contains(pos);
	}

	/**
	 * Inert: natural water at rest (never changed since the world generation, not disturbed). Pressure treats it as a
	 * wall, moisture does not absorb it, the slow checks ignore it. Water that a player or the flow has changed stays
	 * real water for them even when it is back at rest (only its flow tasks stop).
	 */
	public static boolean isInert(ServerLevel level, BlockPos pos) {
		return isResting(level, pos) && isNatural(level, pos);
	}

	/** Natural water: never awake since the world generation (only natural water at rest is a reservoir). */
	public static boolean isNatural(ServerLevel level, BlockPos pos) {
		LevelChunk c = chunk(level, pos);
		return c != null && layer(level, c).isNatural(pos);
	}

	/** Wakes the fluid at pos (if any), or marks it active again. */
	public static void wake(ServerLevel level, BlockPos pos) {
		if (!enabled(level) || level.getFluidState(pos).isEmpty()) return;
		LevelChunk c = chunk(level, pos);
		if (c == null) return;
		WakeLayer layer = layer(level, c);
		if (layer.add(pos, level.getGameTime())) {
			io.github.nagsfov.ocwo.util.OcwoProfiler.addSection(level, "rest: water woken", 0L);
			c.markUnsaved();
			CustomFluidTicks.get(level).awakeChunks().add(c.getPos().pack());
			if (traceAt != null && traceAt.equals(pos)) new Throwable("[OCWO] water woken at " + pos.toShortString()).printStackTrace(System.out);
			if (TRACE_EVERY > 0 && (++wakes % TRACE_EVERY == 0 || wakes <= 40)) new Throwable("[OCWO] wake #" + wakes + " at " + pos.toShortString()).printStackTrace(System.out);
		}
	}

	/**
	 * A block changed: its fluid and the fluid of its 6 neighbours wake up, if the change matters to the water (the
	 * fluid changed, or the block lets water through differently). Not during the end of the world generation.
	 */
	public static void onBlockChanged(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
		if (suppressed > 0 || !enabled(level)) return;
		boolean fluidChanged = !oldState.getFluidState().equals(newState.getFluidState());
		boolean passChanged = oldState.isSolid() != newState.isSolid()
				|| oldState.getCollisionShape(level, pos).isEmpty() != newState.getCollisionShape(level, pos).isEmpty();
		if (fluidChanged) touch(level, pos);
		if (fluidChanged || passChanged) wakeAround(level, pos);
	}

	/** The fluid at pos really changed (also in the fluid layer): no longer natural, and it wakes its neighbours. */
	public static void onFluidChanged(ServerLevel level, BlockPos pos) {
		if (suppressed > 0 || !enabled(level)) return;
		touch(level, pos);
		wakeAround(level, pos);
	}

	private static void touch(ServerLevel level, BlockPos pos) {
		LevelChunk c = chunk(level, pos);
		if (c != null) layer(level, c).touch(pos);
	}

	/** A block changed: its fluid and the fluid of its 6 neighbours wake up. */
	public static void wakeAround(ServerLevel level, BlockPos pos) {
		if (suppressed > 0 || !enabled(level)) return;
		wake(level, pos);
		for (Direction d : DIRS) wake(level, pos.relative(d));
	}

	// ------------------------------------------------------------------ reservoir

	/**
	 * The flow wants to change the fluid at pos from {@code from} to {@code to}: if pos is natural water (never changed
	 * since the world generation, awake or not) and the zone's reserve can cover it, the reserve takes the difference and the block keeps its level (returns true: do not
	 * change the block). Otherwise false: the change happens for real (and wakes the block).
	 */
	public static boolean absorbByReserve(ServerLevel level, BlockPos pos, FluidState from, FluidState to) {
		if (!simulating || suppressed > 0 || !enabled(level) || from.isEmpty()) return false;
		if (!to.isEmpty() && !to.getType().isSame(from.getType())) return false;
		if (!isNatural(level, pos)) return false;
		int diff = (to.isEmpty() ? 0 : to.getAmount()) - from.getAmount();
		if (diff == 0) return true;
		// the block would gain (diff > 0): that water goes into the reserve; it would lose: the reserve pays
		if (WaterReserves.get(level).change(level, pos, diff)) return true;
		// or a large body of natural water at rest pays out of its own surface (06, "Réservoirs abstraits")
		return diff < 0 && WaterReservoirs.pay(level, pos, from.getType(), -diff);
	}

	// ------------------------------------------------------------------ going back to rest

	/**
	 * Every second: awake water that has not been active for {@link #SLEEP_TICKS} ticks, with no flow task due and no
	 * pressure body with work around it, goes back to rest. Deterministic (chunks and positions in insertion order).
	 */
	public static int sleepSweep(ServerLevel level) {
		long now = level.getGameTime();
		if (now % SLEEP_SWEEP_PERIOD != 0 || !enabled(level)) return 0;
		CustomFluidTicks ticks = CustomFluidTicks.get(level);
		// chunks no longer new, generation ticks that never ran (chunk unloaded, block changed)
		ticks.newbornChunks().values().removeIf(born -> now - born >= NEWBORN_TICKS);
		if (!GENERATION_TICKS.isEmpty()) GENERATION_TICKS.values().removeIf(due -> now - due > SLEEP_TICKS);
		LongLinkedOpenHashSet chunks = ticks.awakeChunks();
		int slept = 0;
		LongArrayList done = new LongArrayList();
		for (LongIterator it = chunks.iterator(); it.hasNext(); ) {
			long cp = it.nextLong();
			int cx = ChunkPos.getX(cp), cz = ChunkPos.getZ(cp);
			LevelChunk c = level.getChunkSource().getChunkNow(cx, cz);
			if (c == null) {
				done.add(cp);
				continue;
			}
			WakeLayer layer = Services.PLATFORM.getWakeLayer(c);
			if (layer == null || layer.size() == 0) {
				done.add(cp);
				continue;
			}
			boolean changed = false;
			for (ObjectIterator<Long2LongMap.Entry> e = layer.awake.long2LongEntrySet().fastIterator(); e.hasNext(); ) {
				Long2LongMap.Entry en = e.next();
				if (now - en.getLongValue() < SLEEP_TICKS) continue;
				BlockPos p = WakeLayer.pos(en.getLongKey(), cx, cz);
				FluidState fs = level.getFluidState(p);
				if (!fs.isEmpty()) {
					if (level.getFluidTicks().hasScheduledTick(p, fs.getType())) continue;
					if (fs.getType() instanceof FlowingFluid ff && level.getFluidTicks().hasScheduledTick(p, ff.getSource())) continue;
					if (ticks.bodies().hasWorkAround(p)) continue;
					if (moistureAround(ticks, p)) continue; // a block next to it is still soaking
				}
				e.remove();
				changed = true;
				slept++;
				ticks.onBlockChanged(p); // pressure: this block is a wall again
			}
			if (changed) c.markUnsaved();
			if (layer.size() == 0) done.add(cp);
		}
		for (int i = 0; i < done.size(); i++) chunks.remove(done.getLong(i));
		return slept;
	}

	private static boolean moistureAround(CustomFluidTicks ticks, BlockPos p) {
		for (Direction d : DIRS) if (ticks.moisture().isActive(p.relative(d))) return true;
		return false;
	}

	// ------------------------------------------------------------------ commands

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
