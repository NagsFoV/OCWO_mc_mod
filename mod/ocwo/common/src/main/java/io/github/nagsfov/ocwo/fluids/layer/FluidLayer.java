package io.github.nagsfov.ocwo.fluids.layer;

import io.github.nagsfov.ocwo.OcwoBootConfig;
import io.github.nagsfov.ocwo.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import io.github.nagsfov.ocwo.util.ArrayUtils;


public record FluidLayer(SparseChunkStorage<FluidState> data) {

	public static final String DATA_KEY = "data";
	public static final String DICTIONARY_KEY = "dictionary";
	public static final ByteTag NULL_TAG = ByteTag.valueOf((byte) 0);

	public FluidLayer() {
		this(new SparseChunkStorage<>(FluidState.class));
	}

	public static FluidState getFluidState(int x, int y, int z, ChunkAccess chunk) {
		FluidLayer layer = get(chunk);
		if (layer == null) return null;
		return layer.data.get(x, y - chunk.getMinY(), z);
	}

	public static FluidState getFluidState(BlockPos pos, ChunkAccess chunk) {
		FluidLayer layer = get(chunk);
		if (layer == null) return null;
		return layer.data.get(pos.getX(), pos.getY() - chunk.getMinY(), pos.getZ());
	}

	public static boolean setFluidState(BlockPos pos, ChunkAccess chunk, FluidState fs) {
		if (fs != null && fs.isEmpty()) fs = null;
		return getOrCreate(chunk).data.set(pos.getX(), pos.getY() - chunk.getMinY(), pos.getZ(), fs);
	}

	public static boolean resetFluidState(BlockPos pos, ChunkAccess chunk) {
		FluidLayer layer = get(chunk);
		if (layer == null) return false;
		return layer.data.set(pos.getX(), pos.getY() - chunk.getMinY(), pos.getZ(), null);
	}

	public static FluidState getAndResetFluidState(BlockPos pos, ChunkAccess chunk) {
		FluidLayer layer = get(chunk);
		if (layer == null) return null;
		int x = pos.getX();
		int y = pos.getY() - chunk.getMinY();
		int z = pos.getZ();
		FluidState ret = layer.data.get(x, y, z);
		if (ret != null) layer.data.set(x, y, z, null);
		return ret;
	}

	public static FluidLayer get(ChunkAccess chunk) {
		return Services.PLATFORM.getFluidLayer(chunk);
	}

	public static FluidLayer getOrCreate(ChunkAccess chunk) {
		return Services.PLATFORM.getOrCreateFluidLayer(chunk);
	}

	public static void update(ChunkAccess chunk) {
		FluidLayer old = Services.PLATFORM.setFluidLayer(chunk, null);
		if (old != null) {
			Services.PLATFORM.setFluidLayer(chunk, old);
		}
	}

	public void set(LevelChunk chunk) {
		Services.PLATFORM.setFluidLayer(chunk, this);
	}

	public static FluidLayer fromNbt(Tag tag) {
		try {
			CompoundTag nbt = tag.asCompound().orElse(null);
			if (nbt == null || nbt.isEmpty()) return new FluidLayer();

			ListTag dictionary = nbt.getListOrEmpty(DICTIONARY_KEY);
			FluidState[] states = new FluidState[dictionary.size()];
			int i = 0;
			for (Tag tag2 : dictionary) {
				if (tag2.getType() == ByteTag.TYPE) {
					states[i++] = null;
					continue;
				}
				FluidState fs = FluidState.CODEC.parse(NbtOps.INSTANCE, tag2).getOrThrow();
				states[i++] = fs;
			}
			long[] d = nbt.getLongArray(DATA_KEY).orElse(ArrayUtils.EMPTY_LONG);
			SparseChunkStorage.Data<FluidState> data = new SparseChunkStorage.Data<>(d, states);
			return new FluidLayer(new SparseChunkStorage<>(data));
		} catch (Exception e) {
			e.printStackTrace(System.err);
			return new FluidLayer();
		}
	}

	public CompoundTag toNbt() {
		CompoundTag root = new CompoundTag();
		if (data.isEmpty()) return root;
		SparseChunkStorage.Data<FluidState> data = this.data().getData();
		root.put(DATA_KEY, new LongArrayTag(data.data()));

		ListTag dictionary = new ListTag();
		for (FluidState fs : data.dictionary()) {
			if (fs == null) {
				dictionary.add(NULL_TAG);
				continue;
			}
			Tag tag = FluidState.CODEC.encodeStart(NbtOps.INSTANCE, fs).getOrThrow();
			dictionary.add(tag);
		}

		root.put(DATA_KEY, new LongArrayTag(data.data()));
		root.put(DICTIONARY_KEY, dictionary);
		return root;
	}

	public static FluidLayer read(FriendlyByteBuf buf) {
		long[] data = buf.readLongArray();
		int[] indexes = buf.readVarIntArray();
		FluidState[] states = new FluidState[indexes.length];
		for (int i = 0; i < indexes.length; i++) {
			int index = indexes[i];
			if (index == 0) {
				states[i] = null;
				continue;
			}
			states[i] = Fluid.FLUID_STATE_REGISTRY.byId(index - 1);
		}
		SparseChunkStorage.Data<FluidState> d = new SparseChunkStorage.Data<>(data, states);
		return new FluidLayer(new SparseChunkStorage<>(d));
	}


	public static void writeToBuffer(FriendlyByteBuf buf, FluidLayer layer) {
		SparseChunkStorage.Data<FluidState> d = layer.data().getData();
		FluidState[] states = d.dictionary();
		int[] indexes = new int[states.length];
		for (int i = 0; i < indexes.length; i++) {
			FluidState fs = states[i];
			if (fs == null) {
				indexes[i] = 0;
				continue;
			}
			indexes[i] = Fluid.FLUID_STATE_REGISTRY.getId(fs) + 1;
		}
		buf.writeLongArray(d.data());
		buf.writeVarIntArray(indexes);
	}

	public static void init() {
		Services.PLATFORM.registerFluidLayer(!OcwoBootConfig.INSTANCE.isServerOnly());
	}
}
