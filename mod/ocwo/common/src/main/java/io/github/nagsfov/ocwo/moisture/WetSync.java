package io.github.nagsfov.ocwo.moisture;

import io.github.nagsfov.ocwo.fluids.CustomFluidTicks;
import io.github.nagsfov.ocwo.platform.Services;
import io.github.nagsfov.ocwo.util.OcwoProfiler;
import it.unimi.dsi.fastutil.ints.Int2ByteLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * What the clients need for the darkening of wet blocks (03, "Rendu visuel des stades d'humidité"): for each visible
 * block of wet ground (at least one face against a block that does not hide it), how much wetter than at its
 * generation it is, in stages (0 to 3: sec, humide, mouillé, saturé). A block at its generation moisture keeps its
 * vanilla look, so untouched terrain costs nothing: only the sections that the simulation disturbed (they have a
 * moisture array) can have such blocks, and only changes are sent.
 * <ul>
 *   <li>With a chunk sent to a player: its visible wetter blocks ({@link WetBlocksPayload} full).</li>
 *   <li>Afterwards: a block whose step changes (its moisture stage changed, or its block changed), and the blocks a
 *   change uncovers, to the players who see the chunk, grouped per chunk and sent at the end of the tick.</li>
 * </ul>
 */
public final class WetSync {

	private static final Direction[] DIRS = Direction.values();

	/** Per level: chunk -> (section Y and cell, packed) -> step, waiting for the end of the tick. */
	private static final Map<ServerLevel, Long2ObjectLinkedOpenHashMap<Int2ByteLinkedOpenHashMap>> PENDING = new WeakHashMap<>();

	/** Diagnostics: entries sent, packets sent, full chunk packets sent, changes queued (since the server started). */
	public static long entriesSent, packetsSent, chunksSent, queued;

	private WetSync() {
	}

	/** Darkening step of a block: its moisture stage above the stage of its generation moisture (0 when not wet ground). */
	public static int step(MoistureSimulation sim, BlockPos pos, BlockState state, int units) {
		MoistureProps p = MoistureData.get(state);
		if (p == null || !p.holdsMoisture() || io.github.nagsfov.ocwo.sponge.OcwoSponges.isAnySponge(state)) return 0;
		int cap = p.capacity();
		int now = Moisture.Stage.of(units, cap).ordinal();
		if (now == 0) return 0;
		int gen = Moisture.Stage.of(sim.equilibrium(pos, state), cap).ordinal();
		return Math.max(0, now - gen);
	}

	/** At least one face of the block can be seen (its neighbour there does not hide a full face). */
	public static boolean visible(ServerLevel level, BlockPos pos) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (Direction d : DIRS) {
			m.setWithOffset(pos, d);
			if (!level.isLoaded(m)) continue;
			if (!level.getBlockState(m).isSolidRender()) return true;
		}
		return false;
	}

	/**
	 * The moisture of a block changed (called by the simulation). Something is sent only when its stage changed, it
	 * is visible, and its darkening step changed.
	 */
	public static void onUnitsChanged(ServerLevel level, MoistureSimulation sim, BlockPos pos, int before, int after) {
		if (before == after) return;
		BlockState state = level.getBlockState(pos);
		MoistureProps p = MoistureData.get(state);
		if (p == null || !p.holdsMoisture()) return;
		int cap = p.capacity();
		if (Moisture.Stage.of(before, cap) == Moisture.Stage.of(after, cap)) return;
		int was = step(sim, pos, state, before), now = step(sim, pos, state, after);
		if (was == now || !visible(level, pos)) return;
		queue(level, pos, now);
	}

	/**
	 * A block of the level changed. If the old or the new block holds moisture, its own step (the new block may hold
	 * none, or be at another stage); if the old block hid its neighbours and the new one does not, the steps of the
	 * neighbours it uncovers (never sent while they were hidden). Nothing for the rest (water that moves, plants...).
	 * Only in sections that have moisture: elsewhere every block is at its generation moisture (step 0).
	 */
	public static void onBlockChanged(ServerLevel level, MoistureSimulation sim, BlockPos pos, BlockState oldState, BlockState newState) {
		boolean holds = holds(oldState) || holds(newState);
		boolean uncovers = oldState.isSolidRender() && !newState.isSolidRender();
		if (!holds && !uncovers) return;
		LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
		if (chunk == null) return;
		MoistureLayer layer = Services.PLATFORM.getMoistureLayer(chunk);
		if (layer == null || layer.isEmpty()) return;
		if (holds && layer.has(chunk, pos)) queue(level, pos, step(sim, pos, newState, layer.get(chunk, pos)));
		if (!uncovers) return;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (Direction d : DIRS) {
			m.setWithOffset(pos, d);
			LevelChunk c = (m.getX() >> 4) == (pos.getX() >> 4) && (m.getZ() >> 4) == (pos.getZ() >> 4) ? chunk
					: level.getChunkSource().getChunkNow(m.getX() >> 4, m.getZ() >> 4);
			if (c == null) continue;
			MoistureLayer l = c == chunk ? layer : Services.PLATFORM.getMoistureLayer(c);
			if (l == null || !l.has(c, m)) continue;
			int s = step(sim, m, c.getBlockState(m), l.get(c, m));
			if (s > 0) queue(level, m.immutable(), s);
		}
	}

	private static boolean holds(BlockState state) {
		MoistureProps p = MoistureData.get(state);
		return p != null && p.holdsMoisture();
	}

	private static void queue(ServerLevel level, BlockPos pos, int step) {
		queued++;
		var chunks = PENDING.computeIfAbsent(level, k -> new Long2ObjectLinkedOpenHashMap<>());
		var cells = chunks.computeIfAbsent(ChunkPos.pack(pos), k -> new Int2ByteLinkedOpenHashMap());
		cells.put(WetBlocksPayload.pack(pos.getY() >> 4, cell(pos), 0), (byte) step); // key without the step: the last one wins
	}

	private static int cell(BlockPos pos) {
		return ((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15);
	}

	/** Entries waiting for the end of the tick in this level (tests). */
	public static int pendingCount(ServerLevel level) {
		var chunks = PENDING.get(level);
		if (chunks == null) return 0;
		int n = 0;
		for (var c : chunks.values()) n += c.size();
		return n;
	}

	/** Step waiting to be sent for this block, or -1 (tests). */
	public static int pendingStep(ServerLevel level, BlockPos pos) {
		var chunks = PENDING.get(level);
		var cells = chunks == null ? null : chunks.get(ChunkPos.pack(pos));
		if (cells == null) return -1;
		for (var e : cells.int2ByteEntrySet()) {
			int k = e.getIntKey();
			if (WetBlocksPayload.sectionY(k) == pos.getY() >> 4 && WetBlocksPayload.cell(k) == cell(pos)) return e.getByteValue();
		}
		return -1;
	}

	/** End of the tick: the changes go to the players who see their chunk (nothing kept for the others). */
	public static void flush(ServerLevel level) {
		var chunks = PENDING.get(level);
		if (chunks == null || chunks.isEmpty()) return;
		long t0 = OcwoProfiler.isProfiling(level) ? System.nanoTime() : 0L;
		for (var e : chunks.long2ObjectEntrySet()) {
			ChunkPos cp = new ChunkPos(ChunkPos.getX(e.getLongKey()), ChunkPos.getZ(e.getLongKey()));
			List<ServerPlayer> players = level.getChunkSource().chunkMap.getPlayers(cp, false);
			if (players.isEmpty()) continue;
			int[] entries = new int[e.getValue().size()];
			int i = 0;
			for (var c : e.getValue().int2ByteEntrySet()) entries[i++] = (c.getIntKey() & ~3) | (c.getByteValue() & 3);
			WetBlocksPayload payload = new WetBlocksPayload(cp.x(), cp.z(), false, entries);
			for (ServerPlayer player : players) {
				Services.PLATFORM.sendWetBlocks(player, payload);
				packetsSent++;
				entriesSent += entries.length;
			}
		}
		chunks.clear();
		if (t0 != 0L) OcwoProfiler.addSection(level, "tick: wet blocks to clients", System.nanoTime() - t0);
	}

	/** A chunk was sent to a player: the visible blocks of it that are wetter than at their generation. */
	public static void onChunkSent(ServerPlayer player, ServerLevel level, LevelChunk chunk) {
		long t0 = OcwoProfiler.isProfiling(level) ? System.nanoTime() : 0L;
		int[] entries = chunkEntries(level, chunk);
		if (entries.length > 0) {
			Services.PLATFORM.sendWetBlocks(player, new WetBlocksPayload(chunk.getPos().x(), chunk.getPos().z(), true, entries));
			chunksSent++;
			entriesSent += entries.length;
		}
		if (t0 != 0L) OcwoProfiler.addSection(level, "hook: wet blocks with a chunk", System.nanoTime() - t0);
	}

	/** The visible blocks of a chunk that are wetter than at their generation (public for the tests). */
	public static int[] chunkEntries(ServerLevel level, LevelChunk chunk) {
		MoistureLayer layer = Services.PLATFORM.getMoistureLayer(chunk);
		if (layer == null || layer.isEmpty()) return new int[0];
		MoistureSimulation sim = CustomFluidTicks.get(level).moisture();
		IntArrayList out = new IntArrayList();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
		for (int index : layer.sectionIndexes()) {
			int sy = chunk.getSectionYFromSectionIndex(index);
			short[] cells = layer.sectionCells(index);
			if (cells == null || chunk.getSections()[index].hasOnlyAir()) continue;
			for (int k = 0; k < 4096 && out.size() < WetBlocksPayload.MAX_ENTRIES; k++) {
				int units = cells[k];
				if (units == 0) continue;
				m.set(x0 | (k & 15), (sy << 4) | (k >> 8), z0 | ((k >> 4) & 15));
				BlockState state = chunk.getBlockState(m);
				MoistureProps p = MoistureData.get(state);
				// cheap tests first (most blocks of a section are buried), the generation moisture last
				if (p == null || !p.holdsMoisture() || Moisture.Stage.of(units, p.capacity()).ordinal() == 0 || !visible(level, m)) continue;
				int s = step(sim, m, state, units);
				if (s > 0) out.add(WetBlocksPayload.pack(sy, k, s));
			}
		}
		return out.toIntArray();
	}
}
