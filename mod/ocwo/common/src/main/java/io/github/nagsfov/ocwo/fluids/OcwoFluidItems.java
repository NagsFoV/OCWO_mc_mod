package io.github.nagsfov.ocwo.fluids;

import io.github.nagsfov.ocwo.platform.Services;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import io.github.nagsfov.ocwo.Ocwo;
import io.github.nagsfov.ocwo.fluids.item.FluidLevelsStorage;
import io.github.nagsfov.ocwo.fluids.item.UniversalBucket;

import java.util.function.Function;
import java.util.function.UnaryOperator;

public class OcwoFluidItems {

	public static final DataComponentType<FluidLevelsStorage> FLUID_HOLDER_COMPONENT = registerComponent(
			"stored_fluid",
			b -> b.networkSynchronized(FluidLevelsStorage.STREAM_CODEC)
					.persistent(FluidLevelsStorage.CODEC)
	);

	public static final UniversalBucket UNIVERSAL_BUCKET = registerItem(
			"universal_bucket",
			UniversalBucket::new
	);

	private static <T> DataComponentType<T> registerComponent(String id, UnaryOperator<DataComponentType.Builder<T>> builder) {
		if (!Ocwo.fluidItemsEnabled()) return null;
		return Registry.register(
				BuiltInRegistries.DATA_COMPONENT_TYPE,
				Identifier.fromNamespaceAndPath(Ocwo.MOD_ID, id),
				builder.apply(DataComponentType.builder()).build()
		);
	}

	private static <T extends Item> T registerItem(String id, Function<Item.Properties, T> function) {
		if (!Ocwo.fluidItemsEnabled()) return null;
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Ocwo.MOD_ID, id));
		Item.Properties properties = new Item.Properties();
		properties.setId(key);
		return Registry.register(
				BuiltInRegistries.ITEM,
				key.identifier(),
				function.apply(properties)
		);
	}

	public static void init() {
		//FluidStorage.ITEM.registerForItems(
		//		(itemStack, context) -> new FluidStorageItemComponent(context),
		//		UNIVERSAL_BUCKET
		//);
		//noinspection DataFlowIssue
		Services.PLATFORM.registerTooltipComponent(FLUID_HOLDER_COMPONENT);
	}
}
