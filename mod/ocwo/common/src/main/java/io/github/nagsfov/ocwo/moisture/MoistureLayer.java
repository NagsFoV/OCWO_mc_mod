package io.github.nagsfov.ocwo.moisture;

import com.mojang.serialization.Codec;
import io.github.nagsfov.ocwo.Ocwo;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Moisture of the blocks of one chunk, in units (see {@link Moisture}). Sparse: a section (16x16x16) without an
 * array is at its equilibrium (generation moisture, derived from depth and biome by MoistureSimulation, nothing
 * stored); a section gets its array, filled with that equilibrium, as soon as one of its blocks is disturbed. Only
 * sections with an array are simulated (random ticks). Saved with the chunk; never synced to clients (yet).
 */
public final class MoistureLayer {

	public static final Identifier ID = Identifier.fromNamespaceAndPath(Ocwo.MOD_ID, "moisture");
	public static final Codec<MoistureLayer> CODEC = ExtraCodecs.NBT.xmap(MoistureLayer::fromNbt, MoistureLayer::toNbt);

	/** Section index (chunk.getSectionIndex) -> 4096 cells. */
	private final Int2ObjectMap<short[]> sections = new Int2ObjectOpenHashMap<>();

	public MoistureLayer() {
	}

	private static int cell(BlockPos pos) {
		return ((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15);
	}

	public boolean has(ChunkAccess chunk, BlockPos pos) {
		return sections.containsKey(chunk.getSectionIndex(pos.getY()));
	}

	/** Stored value (the section must have an array, see {@link #has}). */
	public int get(ChunkAccess chunk, BlockPos pos) {
		short[] s = sections.get(chunk.getSectionIndex(pos.getY()));
		return s == null ? 0 : s[cell(pos)];
	}

	/** Gives the section of pos its array; {@code init} gives each cell (index y << 8 | z << 4 | x) its value. */
	public void ensure(ChunkAccess chunk, BlockPos pos, java.util.function.IntUnaryOperator init) {
		int index = chunk.getSectionIndex(pos.getY());
		if (sections.containsKey(index)) return;
		short[] s = new short[4096];
		for (int k = 0; k < 4096; k++) s[k] = (short) Math.max(0, Math.min(Short.MAX_VALUE, init.applyAsInt(k)));
		sections.put(index, s);
	}

	/** Sets a stored value (the section must have an array, see {@link #ensure}). */
	public void set(ChunkAccess chunk, BlockPos pos, int units) {
		short[] s = sections.get(chunk.getSectionIndex(pos.getY()));
		if (s == null) return;
		s[cell(pos)] = (short) Math.max(0, Math.min(Short.MAX_VALUE, units));
	}

	/** Section indexes that have an array (random ticks). */
	public int[] sectionIndexes() {
		return sections.keySet().toIntArray();
	}

	public int sectionCount() {
		return sections.size();
	}

	public boolean isEmpty() {
		return sections.isEmpty();
	}

	/** Total moisture of the chunk, in units (tests). */
	public long total() {
		long sum = 0;
		for (short[] s : sections.values()) for (short v : s) sum += v;
		return sum;
	}

	// ------------------------------------------------------------------ saving

	public static MoistureLayer fromNbt(Tag tag) {
		MoistureLayer layer = new MoistureLayer();
		CompoundTag root = tag.asCompound().orElse(null);
		if (root == null) return layer;
		for (Tag t : root.getListOrEmpty("sections")) {
			CompoundTag sec = t.asCompound().orElse(null);
			if (sec == null) continue;
			int index = sec.getIntOr("i", 0);
			int[] packed = sec.getIntArray("d").orElse(new int[0]);
			if (packed.length != 2048) continue;
			short[] s = new short[4096];
			for (int k = 0; k < 2048; k++) {
				s[2 * k] = (short) (packed[k] & 0xFFFF);
				s[2 * k + 1] = (short) (packed[k] >>> 16);
			}
			layer.sections.put(index, s);
		}
		return layer;
	}

	public CompoundTag toNbt() {
		CompoundTag root = new CompoundTag();
		ListTag list = new ListTag();
		for (Int2ObjectMap.Entry<short[]> e : sections.int2ObjectEntrySet()) {
			short[] s = e.getValue();
			int[] packed = new int[2048];
			for (int k = 0; k < 2048; k++) {
				packed[k] = (s[2 * k] & 0xFFFF) | ((s[2 * k + 1] & 0xFFFF) << 16);
			}
			// an all-zero section is kept: without its array it would be back at its (wetter) equilibrium
			CompoundTag sec = new CompoundTag();
			sec.putInt("i", e.getIntKey());
			sec.put("d", new IntArrayTag(packed));
			list.add(sec);
		}
		root.put("sections", list);
		return root;
	}
}
