package io.github.nagsfov.ocwo.sponge;

import io.github.nagsfov.ocwo.Ocwo;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * OCWO sponge blocks (see 03-humidite-et-etats-des-blocs.md, "Éponges"): a stretched sponge (two blocks, pulled by a
 * sticky piston while held on the other side) and a double sponge (two sponges pressed together by a piston).
 * Not obtainable as items. Registered only when the mod runs on both sides (not in server-only mode).
 */
public final class OcwoSponges {

	@Nullable
	public static final Block STRETCHED_SPONGE = register("stretched_sponge", StretchedSpongeBlock::new);
	@Nullable
	public static final Block COMPRESSED_SPONGE = register("compressed_sponge", CompressedSpongeBlock::new);

	private OcwoSponges() {
	}

	private static Block register(String id, Function<BlockBehaviour.Properties, Block> factory) {
		if (!Ocwo.fluidItemsEnabled()) return null;
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(Ocwo.MOD_ID, id));
		BlockBehaviour.Properties props = BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(0.6F)
				.sound(SoundType.SPONGE).pushReaction(PushReaction.IMMOVEABLE).setId(key);
		if (id.equals("stretched_sponge")) props = props.noLootTable(); // one half destroyed: the other half is the sponge again
		return Registry.register(BuiltInRegistries.BLOCK, key, factory.apply(props));
	}

	/** Loads the class (registers the blocks). */
	public static void init() {
	}

	/** A plain sponge, dry or wet. */
	public static boolean isSponge(BlockState state) {
		return state.is(Blocks.SPONGE) || state.is(Blocks.WET_SPONGE);
	}

	public static boolean isStretched(BlockState state) {
		return STRETCHED_SPONGE != null && state.is(STRETCHED_SPONGE);
	}

	public static boolean isCompressed(BlockState state) {
		return COMPRESSED_SPONGE != null && state.is(COMPRESSED_SPONGE);
	}

	/** Any block of the sponge family. */
	public static boolean isAnySponge(BlockState state) {
		return isSponge(state) || isStretched(state) || isCompressed(state);
	}
}
