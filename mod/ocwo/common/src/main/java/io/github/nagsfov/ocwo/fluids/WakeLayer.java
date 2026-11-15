package io.github.nagsfov.ocwo.fluids;

import com.mojang.serialization.Codec;
import io.github.nagsfov.ocwo.Ocwo;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;

/**
 * Water that is awake in one chunk (see {@link WaterRest}): the positions of the fluid blocks that have been disturbed.
 * Every other fluid block of the chunk is at rest. Saved with the chunk; a chunk without this layer (just generated,
 * or saved before this system) has all its fluid at rest.
 */
public final class WakeLayer {

	public static final Identifier ID = Identifier.fromNamespaceAndPath(Ocwo.MOD_ID, "awake_water");
	public static final Codec<WakeLayer> CODEC = ExtraCodecs.NBT.xmap(WakeLayer::fromNbt, WakeLayer::toNbt);

	/** Positions packed with {@link #key}. */
	private final LongOpenHashSet awake = new LongOpenHashSet();

	/** Position inside the chunk: y (full range), z and x (0..15). */
	static long key(BlockPos pos) {
		return ((long) pos.getY() << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15);
	}

	public boolean contains(BlockPos pos) {
		return awake.contains(key(pos));
	}

	public boolean add(BlockPos pos) {
		return awake.add(key(pos));
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
		for (long k : root.getLongArray("awake").orElse(new long[0])) layer.awake.add(k);
		return layer;
	}

	public Tag toNbt() {
		CompoundTag root = new CompoundTag();
		root.put("awake", new LongArrayTag(awake.toLongArray()));
		return root;
	}
}
