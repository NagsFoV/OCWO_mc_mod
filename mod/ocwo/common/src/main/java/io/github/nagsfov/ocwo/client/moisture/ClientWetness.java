package io.github.nagsfov.ocwo.client.moisture;

import io.github.nagsfov.ocwo.moisture.WetBlocksPayload;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client side of the darkening of wet blocks (see WetSync): the darkening step (0 to 3) of the visible blocks that are
 * wetter than at their generation, per chunk section, as the server sent them. Read by the section meshing (worker
 * threads: vanilla's ModelBlockRenderer, or Sodium's BlockRenderer), written on the client thread. A changed section
 * is rebuilt; the multiplier is applied to the vertex colours when it is built, nothing per frame.
 */
public final class ClientWetness {

	/** Colour multiplier of each step: as generated, one, two and three stages wetter. */
	private static final float[] FACTORS = {1.0F, 0.86F, 0.74F, 0.64F};

	/** Section (SectionPos.asLong) -> step of each cell (y << 8 | z << 4 | x). */
	private static final Map<Long, byte[]> SECTIONS = new ConcurrentHashMap<>();
	/** Chunk (ChunkPos.pack) -> section Ys that have an array. */
	private static final Map<Long, IntOpenHashSet> CHUNKS = new ConcurrentHashMap<>();

	private ClientWetness() {
	}

	/** Colour multiplier of the block at pos (1 when it looks as generated). Any thread. */
	public static float factor(BlockPos pos) {
		if (SECTIONS.isEmpty()) return 1.0F;
		byte[] cells = SECTIONS.get(SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
		if (cells == null) return 1.0F;
		return FACTORS[cells[((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15)] & 3];
	}

	/** Client thread: a packet from the server. The sections it changes are rebuilt. */
	public static void receive(WetBlocksPayload p) {
		long chunk = ChunkPos.pack(p.chunkX(), p.chunkZ());
		IntOpenHashSet dirty = new IntOpenHashSet();
		if (p.full()) {
			// the whole chunk: what we had for it is replaced
			IntOpenHashSet old = CHUNKS.remove(chunk);
			if (old != null) {
				for (int sy : old) SECTIONS.remove(SectionPos.asLong(p.chunkX(), sy, p.chunkZ()));
				dirty.addAll(old);
			}
		}
		for (int e : p.entries()) {
			int sy = WetBlocksPayload.sectionY(e);
			long key = SectionPos.asLong(p.chunkX(), sy, p.chunkZ());
			byte[] cells = SECTIONS.get(key);
			if (cells == null) {
				if (WetBlocksPayload.step(e) == 0) continue; // nothing to clear
				cells = new byte[4096];
				SECTIONS.put(key, cells);
				CHUNKS.computeIfAbsent(chunk, k -> new IntOpenHashSet()).add(sy);
			}
			cells[WetBlocksPayload.cell(e)] = (byte) WetBlocksPayload.step(e);
			dirty.add(sy);
		}
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		for (int sy : dirty) mc.level.setSectionRangeDirty(p.chunkX(), sy, p.chunkZ(), p.chunkX(), sy, p.chunkZ());
	}

	/** Client thread: the chunk was unloaded. */
	public static void dropChunk(int chunkX, int chunkZ) {
		IntOpenHashSet old = CHUNKS.remove(ChunkPos.pack(chunkX, chunkZ));
		if (old != null) for (int sy : old) SECTIONS.remove(SectionPos.asLong(chunkX, sy, chunkZ));
	}

	/** Joined or left a world. */
	public static void clear() {
		SECTIONS.clear();
		CHUNKS.clear();
	}

	/** Sections holding wet blocks (diagnostics). */
	public static int sectionCount() {
		return SECTIONS.size();
	}
}
