package io.github.nagsfov.ocwo.gametest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * OCWO dev tools run on the test server (not tests): gradlew :fabric:runBlockDump
 * Writes every registered block with the data used to classify it into OCWO materials.
 * Output path: system property ocwo.dump (default: ocwo-blocks.json in the run directory).
 */
public class ToolsGameTests {

	private static Map<SoundType, String> soundNames() {
		Map<SoundType, String> names = new IdentityHashMap<>();
		for (Field f : SoundType.class.getDeclaredFields()) {
			if (Modifier.isStatic(f.getModifiers()) && f.getType() == SoundType.class) {
				try {
					f.setAccessible(true);
					names.putIfAbsent((SoundType) f.get(null), f.getName().toLowerCase());
				} catch (ReflectiveOperationException ignored) {
				}
			}
		}
		return names;
	}

	@GameTest(maxTicks = 100)
	public void dumpBlocks(GameTestHelper h) throws Exception {
		Map<SoundType, String> sounds = soundNames();
		JsonArray blocks = new JsonArray();
		for (Block block : BuiltInRegistries.BLOCK) {
			Holder<Block> holder = BuiltInRegistries.BLOCK.wrapAsHolder(block);
			BlockState s = block.defaultBlockState();
			JsonObject o = new JsonObject();
			o.addProperty("id", BuiltInRegistries.BLOCK.getKey(block).toString());
			JsonArray tags = new JsonArray();
			holder.tags().map(t -> t.location().toString()).sorted().forEach(tags::add);
			o.add("tags", tags);
			o.addProperty("full", s.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
			o.addProperty("noCollision", s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty());
			o.addProperty("solid", s.isSolid());
			o.addProperty("occludes", s.canOcclude());
			o.addProperty("waterloggable", s.hasProperty(BlockStateProperties.WATERLOGGED));
			o.addProperty("liquidContainer", block instanceof LiquidBlockContainer);
			o.addProperty("liquid", s.liquid());
			o.addProperty("air", s.isAir());
			o.addProperty("replaceable", s.canBeReplaced());
			o.addProperty("hardness", s.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
			o.addProperty("blastResistance", block.getExplosionResistance());
			o.addProperty("sound", sounds.getOrDefault(s.getSoundType(), "other"));
			o.addProperty("blockEntity", s.hasBlockEntity());
			o.addProperty("light", s.getLightEmission());
			o.addProperty("randomTicks", s.isRandomlyTicking());
			o.addProperty("class", block.getClass().getSimpleName());
			JsonArray props = new JsonArray();
			s.getProperties().forEach(p -> props.add(p.getName()));
			o.add("properties", props);
			blocks.add(o);
		}
		Path out = Path.of(System.getProperty("ocwo.dump", "ocwo-blocks.json"));
		Files.writeString(out, new GsonBuilder().create().toJson(blocks));
		System.out.println("[OCWO-TOOL] " + blocks.size() + " blocks written to " + out.toAbsolutePath());
		h.succeed();
	}
}
