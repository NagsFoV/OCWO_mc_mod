package io.github.nagsfov.ocwo.fluids;

import com.mojang.serialization.Codec;
import io.github.nagsfov.ocwo.Ocwo;
import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;

/**
 * Water that is awake in one chunk (see {@link WaterRest}): the positions of the fluid blocks that have been disturbed,
 * with the game time of their last activity (for going back to rest). Every other fluid block of the chunk is at
 * rest. Saved with the chunk; a chunk without this layer (just generated, or saved before this system) has all its
 * fluid at rest.
 */
public final class WakeLayer {

	public static final Identifier ID = Identifier.fromNamespaceAndPath(Ocwo.MOD_ID, "awake_water");
	public static final Codec<WakeLayer> CODEC = ExtraCodecs.NBT.xmap(WakeLayer::fromNbt, WakeLayer::toNbt);

	/** Positions packed with {@link #key} -> game time of the last activity (insertion order: deterministic). */
	final Long2LongLinkedOpenHashMap awake = new Long2LongLinkedOpenHashMap();
	/** Positions whose fluid has really changed once: no longer natural water (not a reservoir), even at rest again. */
	final it.unimi.dsi.fastutil.longs.LongOpenHashSet touched = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
	/** The chunk was looked at once by this version (existing worlds: its moving water was woken then). */
	boolean initialized;

	/** Position inside the chunk: y (full range), z and x (0..15). */
	static long key(BlockPos pos) {
		return ((long) pos.getY() << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15);
	}

	static BlockPos pos(long key, int chunkX, int chunkZ) {
		int y = (int) (key >> 8);
		return new BlockPos((chunkX << 4) | (int) (key & 15), y, (chunkZ << 4) | (int) ((key >> 4) & 15));
	}

	public boolean contains(BlockPos pos) {
		return awake.containsKey(key(pos));
	}

	/** Marks pos awake (or active again); true if it was at rest. */
	public boolean add(BlockPos pos, long now) {
		long k = key(pos);
		boolean had = awake.containsKey(k);
		awake.put(k, now);
		return !had;
	}

	/** The fluid of pos really changed: it is no longer natural water. */
	public void touch(BlockPos pos) {
		touched.add(key(pos));
	}

	/** Natural water: never awake since the world generation. */
	public boolean isNatural(BlockPos pos) {
		return !touched.contains(key(pos));
	}

	public void remove(BlockPos pos) {
		awake.remove(key(pos));
	}

	public void clear() {
		awake.clear();
	}

	public int size() {
		return awake.size();
	}

	public static WakeLayer fromNbt(Tag tag) {
		WakeLayer layer = new WakeLayer();
		CompoundTag root = tag.asCompound().orElse(null);
		if (root == null) return layer;
		for (long k : root.getLongArray("awake").orElse(new long[0])) layer.awake.put(k, 0L);
		for (long k : root.getLongArray("touched").orElse(new long[0])) layer.touched.add(k);
		layer.initialized = root.getBooleanOr("initialized", false);
		return layer;
	}

	public Tag toNbt() {
		CompoundTag root = new CompoundTag();
		root.put("awake", new LongArrayTag(awake.keySet().toLongArray()));
		root.put("touched", new LongArrayTag(touched.toLongArray()));
		root.putBoolean("initialized", initialized);
		return root;
	}
}
