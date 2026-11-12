package io.github.nagsfov.ocwo.moisture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.nagsfov.ocwo.Ocwo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Moisture data of every block, loaded from data packs (reloadable with /reload):
 * <ul>
 *   <li>{@code data/<ns>/ocwo_materials/<material>.json}: material values and the blocks ("#tag" allowed) that use it;</li>
 *   <li>{@code data/<ns>/ocwo_block_settings/**.json}: per-block settings (capacity, permeability per face or axis,
 *   fixed water).</li>
 * </ul>
 * Generated from the classification page by tools/export_datapack.py.
 */
public final class MoistureData {

	public static final Identifier RELOAD_ID = Identifier.fromNamespaceAndPath(Ocwo.MOD_ID, "moisture_data");
	private static final FileToIdConverter MATERIALS = FileToIdConverter.json("ocwo_materials");
	private static final FileToIdConverter SETTINGS = FileToIdConverter.json("ocwo_block_settings");

	private static volatile Map<Block, MoistureProps> byBlock = Map.of();
	private static volatile List<Map.Entry<TagKey<Block>, MoistureProps>> byTag = List.of();
	private static final Map<Block, Optional<MoistureProps>> TAG_CACHE = new ConcurrentHashMap<>();

	private MoistureData() {
	}

	/** Moisture properties of this block, or null if it holds no moisture. */
	@Nullable
	public static MoistureProps get(BlockState state) {
		Block block = state.getBlock();
		MoistureProps p = byBlock.get(block);
		if (p != null || byTag.isEmpty()) return p;
		return TAG_CACHE.computeIfAbsent(block, b -> {
			for (var e : byTag) if (state.is(e.getKey())) return Optional.of(e.getValue());
			return Optional.empty();
		}).orElse(null);
	}

	public static int blockCount() {
		return byBlock.size();
	}

	// ------------------------------------------------------------------ loading

	private record Raw(Map<Identifier, JsonObject> materials, Map<Identifier, JsonObject> settings) {
	}

	public static final SimplePreparableReloadListener<Raw> LISTENER = new SimplePreparableReloadListener<>() {
		@Override
		protected Raw prepare(ResourceManager manager, ProfilerFiller profiler) {
			return new Raw(read(manager, MATERIALS), read(manager, SETTINGS));
		}

		@Override
		protected void apply(Raw raw, ResourceManager manager, ProfilerFiller profiler) {
			build(raw);
		}
	};

	private static Map<Identifier, JsonObject> read(ResourceManager manager, FileToIdConverter lister) {
		Map<Identifier, JsonObject> out = new HashMap<>();
		for (Map.Entry<Identifier, Resource> e : lister.listMatchingResources(manager).entrySet()) {
			try (Reader reader = e.getValue().openAsReader()) {
				JsonElement json = JsonParser.parseReader(reader);
				out.put(lister.fileToId(e.getKey()), json.getAsJsonObject());
			} catch (Exception ex) {
				Ocwo.LOGGER.error("Could not read moisture data {}: {}", e.getKey(), ex.toString());
			}
		}
		return out;
	}

	private static int getInt(JsonObject o, String key, int fallback) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : fallback;
	}

	private static Block block(String id) {
		Identifier rl = Identifier.tryParse(id);
		return rl == null ? null : BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
	}

	private static void build(Raw raw) {
		Map<Block, MoistureProps> blocks = new IdentityHashMap<>();
		List<Map.Entry<TagKey<Block>, MoistureProps>> tags = new ArrayList<>();
		Map<Block, JsonObject> settings = new IdentityHashMap<>();
		for (JsonObject s : raw.settings().values()) {
			Block b = s.has("block") ? block(s.get("block").getAsString()) : null;
			if (b != null) settings.put(b, s);
		}
		List<Runnable> reverts = new ArrayList<>();
		for (Map.Entry<Identifier, JsonObject> e : raw.materials().entrySet()) {
			JsonObject m = e.getValue();
			String material = e.getKey().getPath();
			int capacityLevels = getInt(m, "capacity", 0);
			int permeability = getInt(m, "permeability", 0);
			boolean stages = m.has("stages") && m.get("stages").getAsBoolean();
			boolean retains = m.has("retains") && m.get("retains").getAsBoolean();
			JsonObject transform = m.has("transform") ? m.getAsJsonObject("transform") : null;
			String target = transform != null ? transform.get("target").getAsString() : null;
			Moisture.Stage stageIn = transform != null && transform.has("stageIn")
					? Moisture.Stage.byId(transform.get("stageIn").getAsString(), Moisture.Stage.SATURE) : Moisture.Stage.SATURE;
			Moisture.Stage stageOut = transform != null && transform.has("stageOut")
					? Moisture.Stage.byId(transform.get("stageOut").getAsString(), null) : null;
			List<String> from = new ArrayList<>();
			if (transform != null && transform.has("from")) transform.getAsJsonArray("from").forEach(x -> from.add(x.getAsString()));

			for (JsonElement el : m.getAsJsonArray("blocks")) {
				String id = el.getAsString();
				if (id.startsWith("#")) {
					Identifier tag = Identifier.tryParse(id.substring(1));
					if (tag != null) tags.add(Map.entry(TagKey.create(Registries.BLOCK, tag),
							props(material, capacityLevels, permeability, stages, retains, null, null)));
					continue;
				}
				Block b = block(id);
				if (b == null) continue;
				Block to = null;
				if (target != null && from.contains(id)) {
					to = "ocwo:same_color_concrete".equals(target)
							? block(id.replace("_concrete_powder", "_concrete"))
							: block(target);
				}
				MoistureProps p = props(material, capacityLevels, permeability, stages, retains, settings.get(b), to);
				p = new MoistureProps(p.material(), p.capacity(), p.permeability(), p.along(), p.across(), p.up(), p.down(),
						p.side(), p.stages(), p.retains(), p.fixedWater(), to, stageIn, null, Moisture.Stage.SEC);
				blocks.put(b, p);
				if (to != null && stageOut != null) {
					Block source = b, dest = to;
					reverts.add(() -> {
						MoistureProps destProps = blocks.get(dest);
						if (destProps != null && destProps.revertTo() == null) blocks.put(dest, destProps.withRevert(source, stageOut));
					});
				}
			}
		}
		reverts.forEach(Runnable::run);
		byBlock = blocks;
		byTag = tags;
		TAG_CACHE.clear();
		Ocwo.LOGGER.info("Loaded moisture data: {} blocks, {} tags", blocks.size(), tags.size());
	}

	private static MoistureProps props(String material, int capacityLevels, int permeability, boolean stages,
	                                   boolean retains, @Nullable JsonObject s, @Nullable Block to) {
		int capacity = capacityLevels;
		int perm = permeability;
		int along = -1, across = -1, up = -1, down = -1, side = -1, fixed = 0;
		if (s != null) {
			capacity = getInt(s, "capacity", capacity);
			perm = getInt(s, "permeability", perm);
			along = getInt(s, "along", -1);
			across = getInt(s, "across", -1);
			up = getInt(s, "up", -1);
			down = getInt(s, "down", -1);
			side = getInt(s, "side", -1);
			fixed = getInt(s, "fixed_water", 0) * Moisture.UNITS_PER_LEVEL;
		}
		return new MoistureProps(material, capacity * Moisture.UNITS_PER_LEVEL, perm, along, across, up, down, side,
				stages, retains, fixed, to, Moisture.Stage.SATURE, null, Moisture.Stage.SEC);
	}
}
