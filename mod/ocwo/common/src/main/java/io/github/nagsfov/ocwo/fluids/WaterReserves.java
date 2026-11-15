package io.github.nagsfov.ocwo.fluids;

import com.mojang.serialization.Codec;
import io.github.nagsfov.ocwo.Ocwo;
import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Water reserves of the zones of a level (4 x 4 chunks each), in water levels (see 06-pression.md, "Réservoir").
 * Water at rest keeps its level: what awake water takes from it, or gives to it, is counted here instead. Exact
 * conservation: visible water plus reserves. A zone's reserve starts at the volume of water of its loaded chunks
 * when it is first used; when it is empty, the water at rest really goes down (it wakes up).
 */
public final class WaterReserves extends SavedData {

	public static final Codec<WaterReserves> CODEC = ExtraCodecs.NBT.xmap(WaterReserves::fromNbt, WaterReserves::toNbt);
	public static final SavedDataType<WaterReserves> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath(Ocwo.MOD_ID, "water_reserves"), WaterReserves::new, CODEC, null);

	/** Zone key -> levels. */
	private final Long2LongLinkedOpenHashMap reserves = new Long2LongLinkedOpenHashMap();

	public static WaterReserves get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(TYPE);
	}

	public static long zone(BlockPos pos) {
		int zx = pos.getX() >> 6, zz = pos.getZ() >> 6; // 4 chunks = 64 blocks
		return ((long) zx << 32) ^ (zz & 0xffffffffL);
	}

	/** Reserve of the zone of pos (computed the first time). */
	public long reserve(ServerLevel level, BlockPos pos) {
		long z = zone(pos);
		if (!reserves.containsKey(z)) {
			reserves.put(z, initialVolume(level, pos));
			setDirty();
		}
		return reserves.get(z);
	}

	/** Adds (or removes, if negative) levels; false (nothing changed) if the reserve cannot cover a removal. */
	public boolean change(ServerLevel level, BlockPos pos, long levels) {
		long r = reserve(level, pos);
		if (r + levels < 0) return false;
		reserves.put(zone(pos), r + levels);
		setDirty();
		return true;
	}

	/** Sum of the reserves (tests, conservation). */
	public long total() {
		long s = 0;
		for (long v : reserves.values()) s += v;
		return s;
	}

	/** Water levels in the loaded chunks of the zone of pos. */
	private static long initialVolume(ServerLevel level, BlockPos pos) {
		int cx0 = (pos.getX() >> 6) << 2, cz0 = (pos.getZ() >> 6) << 2;
		long sum = 0;
		for (int cx = cx0; cx < cx0 + 4; cx++)
			for (int cz = cz0; cz < cz0 + 4; cz++) {
				LevelChunk c = level.getChunkSource().getChunkNow(cx, cz);
				if (c == null) continue;
				for (LevelChunkSection s : c.getSections()) {
					if (s.hasOnlyAir() || !s.maybeHas(st -> !st.getFluidState().isEmpty())) continue;
					for (int y = 0; y < 16; y++)
						for (int z = 0; z < 16; z++)
							for (int x = 0; x < 16; x++) {
								FluidState fs = s.getFluidState(x, y, z);
								if (!fs.isEmpty()) sum += fs.getAmount();
							}
				}
			}
		return sum;
	}

	private static WaterReserves fromNbt(Tag tag) {
		WaterReserves r = new WaterReserves();
		CompoundTag root = tag.asCompound().orElse(null);
		if (root == null) return r;
		long[] keys = root.getLongArray("zones").orElse(new long[0]);
		long[] values = root.getLongArray("levels").orElse(new long[0]);
		for (int i = 0; i < Math.min(keys.length, values.length); i++) r.reserves.put(keys[i], values[i]);
		return r;
	}

	private Tag toNbt() {
		CompoundTag root = new CompoundTag();
		root.put("zones", new LongArrayTag(reserves.keySet().toLongArray()));
		root.put("levels", new LongArrayTag(reserves.values().toLongArray()));
		return root;
	}
}
