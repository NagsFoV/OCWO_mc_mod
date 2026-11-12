package io.github.nagsfov.ocwo.fluids.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.nagsfov.ocwo.platform.Services;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;
import net.minecraft.world.level.material.Fluid;
import io.github.nagsfov.ocwo.Ocwo;
import io.github.nagsfov.ocwo.fluids.FluidUtils;

import java.util.function.Consumer;

/**
 * Fluid stored in an item. PhysEx used Fabric's FluidVariant here; OCWO stores the vanilla {@link Fluid} directly
 * so that the class stays loader-independent.
 */
public record FluidLevelsStorage(Fluid fluid, int levels) implements TooltipProvider {

	public static final FluidLevelsStorage EMPTY = new FluidLevelsStorage(Fluids.EMPTY, 0);

	public static final Codec<FluidLevelsStorage> CODEC = RecordCodecBuilder.create(instance ->
			instance.group(
					BuiltInRegistries.FLUID.byNameCodec().fieldOf("fluid").forGetter(FluidLevelsStorage::fluid),
					Codec.INT.fieldOf("levels").forGetter(FluidLevelsStorage::levels)
			).apply(instance, FluidLevelsStorage::new)
	);

	public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, FluidLevelsStorage> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.registry(Registries.FLUID), FluidLevelsStorage::fluid,
			ByteBufCodecs.VAR_INT, FluidLevelsStorage::levels,
			FluidLevelsStorage::new
	);

	public boolean isEmpty() {
		return this == EMPTY || levels == 0 || fluid == Fluids.EMPTY;
	}

	@Override
	public void addToTooltip(Item.TooltipContext tooltipContext, Consumer<Component> consumer, TooltipFlag tooltipFlag, DataComponentGetter dataComponentGetter) {
		if (!this.isEmpty()) {
			Component fluidName = Services.PLATFORM.getFluidName(this.fluid);
			String amount = "%.02f".formatted((float) this.levels / FluidUtils.MAX_LEVEL);
			consumer.accept(Component.translatable("tooltip." + Ocwo.MOD_ID + ".bucket_content", fluidName, amount));
		}
	}
}
