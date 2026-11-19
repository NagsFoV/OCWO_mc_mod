package io.github.nagsfov.ocwo.fluids;

import com.mojang.serialization.Codec;
import io.github.nagsfov.ocwo.Ocwo;
import io.github.nagsfov.ocwo.moisture.Moisture;
import io.github.nagsfov.ocwo.moisture.MoistureData;
import io.github.nagsfov.ocwo.moisture.MoistureLayer;
import io.github.nagsfov.ocwo.moisture.MoistureProps;
import io.github.nagsfov.ocwo.moisture.MoistureSimulation;
import io.github.nagsfov.ocwo.platform.Services;
import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Water ledger of the zones of a level (4 x 4 chunks each), in moisture units (16 per water level). See
 * 03-humidite-et-etats-des-blocs.md, "Registre d'eau par zone", and 06-pression.md, "Réservoirs de zone". Two
 * invisible stocks per zone:
 * <ul>
 *   <li><b>reserve</b> (ground water): when the flow takes water from natural water (never changed since the world
 *   generation) or gives it some, that water keeps its level and the reserve pays or receives the difference, as long
 *   as it can; the generation moisture of a block that goes away comes back here, and so does moisture left in a full
 *   block that holds none. A zone's reserve starts at 0: it only ever holds water that was somewhere else before;</li>
 *   <li><b>clouds</b>: water that went up (evaporation, no rain yet).</li>
 * </ul>
 * Exact conservation, in units: visible water + block moisture + reserves + clouds. Only the world generation creates
 * water (water blocks and generation moisture).
 */
public final class WaterReserves extends SavedData {

	private static final int U = Moisture.UNITS_PER_LEVEL;
	public static final Codec<WaterReserves> CODEC = ExtraCodecs.NBT.xmap(WaterReserves::fromNbt, WaterReserves::toNbt);
	public static final SavedDataType<WaterReserves> TYPE = new SavedDataType<>(
			Identifier.fromNamespaceAndPath(Ocwo.MOD_ID, "water_reserves"), WaterReserves::new, CODEC, null);

	/** Zone key -> units. */
	private final Long2LongLinkedOpenHashMap reserves = new Long2LongLinkedOpenHashMap();
	private final Long2LongLinkedOpenHashMap clouds = new Long2LongLinkedOpenHashMap();

	public static WaterReserves get(ServerLevel level) {
		return level.getDataStorage().computeIfAbsent(TYPE);
	}

	public static long zone(BlockPos pos) {
		return zone(pos.getX() >> 6, pos.getZ() >> 6); // 4 chunks = 64 blocks
	}

	public static long zone(int zx, int zz) {
		return ((long) zx << 32) ^ (zz & 0xffffffffL);
	}

	public static int zoneX(long zone) {
		return (int) (zone >> 32);
	}

	public static int zoneZ(long zone) {
		return (int) zone;
	}

	/** Reserve of the zone of pos, in units (0 for a zone never used). */
	public long reserveUnits(ServerLevel level, BlockPos pos) {
		return reserves.get(zone(pos));
	}

	/** Reserve of a zone, in units. */
	public long reserveOf(long zone) {
		return reserves.get(zone);
	}

	/** Reserve of the zone of pos in whole water levels. */
	public long reserve(ServerLevel level, BlockPos pos) {
		return Math.floorDiv(reserveUnits(level, pos), U);
	}

	/** Adds (or removes, if negative) levels; false (nothing changed) if the reserve cannot cover a removal. */
	public boolean change(ServerLevel level, BlockPos pos, long levels) {
		long r = reserveUnits(level, pos);
		if (r + levels * U < 0) return false;
		reserves.put(zone(pos), r + levels * U);
		setDirty();
		return true;
	}

	/** Gives moisture units to the reserve (ground water). */
	public void giveUnits(ServerLevel level, BlockPos pos, long units) {
		if (units <= 0) return;
		reserves.put(zone(pos), reserveUnits(level, pos) + units);
		setDirty();
	}

	/** Clouds of the zone of pos, in units. */
	public long clouds(BlockPos pos) {
		return clouds.get(zone(pos));
	}

	/** Clouds of a zone, in units. */
	public long cloudsOf(long zone) {
		return clouds.get(zone);
	}

	/** Sum of the reserves, in units (tests, conservation). */
	public long totalUnits() {
		long s = 0;
		for (long v : reserves.values()) s += v;
		return s;
	}

	/** Sum of the clouds, in units (tests, conservation). */
	public long totalClouds() {
		long s = 0;
		for (long v : clouds.values()) s += v;
		return s;
	}

	// ------------------------------------------------------------------ counting a zone

	/**
	 * What a zone holds, in units, counted in its loaded chunks: visible water (fluid levels x 16), block moisture
	 * (stored, or the generation moisture of the blocks of sections never disturbed), reserve and clouds.
	 */
	public record ZoneCount(long zone, int loadedChunks, long liquidUnits, long moistureUnits, long reserveUnits, long cloudUnits) {
		public long total() {
			return liquidUnits + moistureUnits + reserveUnits + cloudUnits;
		}
	}

	/** Counts the zone of pos (command {@code /ocwo water ledger}, tests). Reads every block of the loaded chunks. */
	public ZoneCount count(ServerLevel level, BlockPos pos) {
		return count(level, zone(pos));
	}

	public ZoneCount count(ServerLevel level, long zone) {
		int cx0 = zoneX(zone) << 2, cz0 = zoneZ(zone) << 2;
		MoistureSimulation sim = CustomFluidTicks.get(level).moisture();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		int loaded = 0;
		long liquid = 0, moisture = 0;
		for (int cx = cx0; cx < cx0 + 4; cx++)
			for (int cz = cz0; cz < cz0 + 4; cz++) {
				LevelChunk c = level.getChunkSource().getChunkNow(cx, cz);
				if (c == null) continue;
				loaded++;
				MoistureLayer layer = Services.PLATFORM.getMoistureLayer(c);
				// water in the fluid layer (inside fences, slabs...) is not in the block states: read through the chunk
				io.github.nagsfov.ocwo.fluids.layer.FluidLayer fl = io.github.nagsfov.ocwo.fluids.layer.FluidLayer.get(c);
				boolean layered = fl != null && !fl.data().isEmpty();
				LevelChunkSection[] sections = c.getSections();
				for (int i = 0; i < sections.length; i++) {
					LevelChunkSection s = sections[i];
					int y0 = c.getSectionYFromSectionIndex(i) << 4;
					m.set(cx << 4, y0, cz << 4);
					boolean stored = layer != null && layer.has(c, m);
					if (stored) moisture += layer.sectionTotal(c, m);
					if (s.hasOnlyAir()) continue;
					boolean fluids = layered || s.maybeHas(st -> !st.getFluidState().isEmpty());
					boolean holds = !stored && s.maybeHas(st -> holdsMoisture(st));
					if (!fluids && !holds) continue;
					for (int y = 0; y < 16; y++)
						for (int z = 0; z < 16; z++)
							for (int x = 0; x < 16; x++) {
								if (fluids) {
									FluidState fs = layered ? c.getFluidState((cx << 4) | x, y0 + y, (cz << 4) | z) : s.getFluidState(x, y, z);
									if (!fs.isEmpty() && fs.getType().isSame(net.minecraft.world.level.material.Fluids.WATER)) liquid += (long) fs.getAmount() * U;
								}
								if (holds) {
									BlockState st = s.getBlockState(x, y, z);
									if (holdsMoisture(st)) moisture += sim.equilibrium(m.set((cx << 4) | x, y0 + y, (cz << 4) | z), st);
								}
							}
				}
			}
		return new ZoneCount(zone, loaded, liquid, moisture, reserves.get(zone), clouds.get(zone));
	}

	private static boolean holdsMoisture(BlockState st) {
		MoistureProps p = MoistureData.get(st);
		return p != null && p.holdsMoisture();
	}

	// ------------------------------------------------------------------ saving

	private static WaterReserves fromNbt(Tag tag) {
		WaterReserves r = new WaterReserves();
		CompoundTag root = tag.asCompound().orElse(null);
		if (root == null) return r;
		long[] keys = root.getLongArray("zones").orElse(new long[0]);
		long[] units = root.getLongArray("units").orElse(null);
		if (units == null) {
			// saved before the ledger (19/11/2026): reserves in levels, kept as they are (see 03, "Registre d'eau par zone")
			long[] levels = root.getLongArray("levels").orElse(new long[0]);
			units = new long[levels.length];
			for (int i = 0; i < levels.length; i++) units[i] = levels[i] * U;
		}
		for (int i = 0; i < Math.min(keys.length, units.length); i++) r.reserves.put(keys[i], units[i]);
		long[] ck = root.getLongArray("cloudZones").orElse(new long[0]);
		long[] cv = root.getLongArray("clouds").orElse(new long[0]);
		for (int i = 0; i < Math.min(ck.length, cv.length); i++) r.clouds.put(ck[i], cv[i]);
		return r;
	}

	private Tag toNbt() {
		CompoundTag root = new CompoundTag();
		root.put("zones", new LongArrayTag(reserves.keySet().toLongArray()));
		root.put("units", new LongArrayTag(reserves.values().toLongArray()));
		root.put("cloudZones", new LongArrayTag(clouds.keySet().toLongArray()));
		root.put("clouds", new LongArrayTag(clouds.values().toLongArray()));
		return root;
	}
}
