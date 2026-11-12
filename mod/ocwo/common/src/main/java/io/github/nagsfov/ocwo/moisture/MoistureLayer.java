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
 * Moisture of the blocks of one chunk, in units (see {@link Moisture}). Sparse: a section (16x16x16) gets its
 * array only once one of its blocks holds moisture. Saved with the chunk; never synced to clients (yet).
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

	public int get(ChunkAccess chunk, BlockPos pos) {
		short[] s = sections.get(chunk.getSectionIndex(pos.getY()));
		return s == null ? 0 : s[cell(pos)];
	}

	public void set(ChunkAccess chunk, BlockPos pos, int units) {
		int index = chunk.getSectionIndex(pos.getY());
		short[] s = sections.get(index);
		if (s == null) {
			if (units == 0) return;
			s = new short[4096];
			sections.put(index, s);
		}
		s[cell(pos)] = (short) Math.max(0, Math.min(Short.MAX_VALUE, units));
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
			boolean any = false;
			int[] packed = new int[2048];
			for (int k = 0; k < 2048; k++) {
				packed[k] = (s[2 * k] & 0xFFFF) | ((s[2 * k + 1] & 0xFFFF) << 16);
				any |= packed[k] != 0;
			}
			if (!any) continue;
			CompoundTag sec = new CompoundTag();
			sec.putInt("i", e.getIntKey());
			sec.put("d", new IntArrayTag(packed));
			list.add(sec);
		}
		root.put("sections", list);
		return root;
	}
}
