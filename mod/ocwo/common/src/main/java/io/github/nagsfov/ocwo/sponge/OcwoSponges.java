package io.github.nagsfov.ocwo.sponge;

import io.github.nagsfov.ocwo.Ocwo;
import io.github.nagsfov.ocwo.platform.Services;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
 * sticky piston while held on the other side) and a double sponge (two sponges pressed together by a piston), and the
 * water a sponge item carries. Not obtainable as items. Registered only when the mod runs on both sides (not in
 * server-only mode).
 */
public final class OcwoSponges {

	/** Water levels of a sponge item (null in server-only mode: a broken sponge then gives its water back around it). */
	@Nullable
	public static final DataComponentType<SpongeWater> SPONGE_WATER = Ocwo.fluidItemsEnabled()
			? Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, Identifier.fromNamespaceAndPath(Ocwo.MOD_ID, "sponge_water"),
			DataComponentType.<SpongeWater>builder().persistent(SpongeWater.CODEC).networkSynchronized(SpongeWater.STREAM_CODEC).build())
			: null;

	@Nullable
	public static final Block STRETCHED_SPONGE = register("stretched_sponge", StretchedSpongeBlock::new, PushReaction.IMMOVEABLE);
	/** Pushed only across its axis (see PistonBaseBlockMixin); along it, pressing it again breaks it. */
	@Nullable
	public static final Block COMPRESSED_SPONGE = register("compressed_sponge", CompressedSpongeBlock::new, PushReaction.PUSH);

	private OcwoSponges() {
	}

	private static Block register(String id, Function<BlockBehaviour.Properties, Block> factory, PushReaction push) {
		if (!Ocwo.fluidItemsEnabled()) return null;
		ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(Ocwo.MOD_ID, id));
		// no loot tables: broken, they drop their sponges themselves, with their water (SpongeLogic)
		BlockBehaviour.Properties props = BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(0.6F)
				.sound(SoundType.SPONGE).pushReaction(push).noLootTable().setId(key);
		return Registry.register(BuiltInRegistries.BLOCK, key, factory.apply(props));
	}

	/** Loads the class (registers the blocks and the item component). */
	public static void init() {
		if (SPONGE_WATER != null) Services.PLATFORM.registerTooltipComponent(SPONGE_WATER);
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

	/** A sponge item (dry or wet). */
	public static boolean isSpongeItem(ItemStack stack) {
		return stack.is(Items.SPONGE) || stack.is(Items.WET_SPONGE);
	}

	/** Water levels a sponge item carries (0 without the component). */
	public static int itemLevels(ItemStack stack) {
		if (SPONGE_WATER == null || !isSpongeItem(stack)) return 0;
		SpongeWater w = stack.get(SPONGE_WATER);
		return w == null ? 0 : Math.max(0, w.levels());
	}

	/** A sponge item holding {@code levels} levels: a wet sponge with the component, or a plain dry sponge. */
	public static ItemStack spongeItem(int levels, int count) {
		if (levels <= 0 || SPONGE_WATER == null) return new ItemStack(Items.SPONGE, count);
		ItemStack stack = new ItemStack(Items.WET_SPONGE, count);
		stack.set(SPONGE_WATER, new SpongeWater(levels));
		return stack;
	}
}
