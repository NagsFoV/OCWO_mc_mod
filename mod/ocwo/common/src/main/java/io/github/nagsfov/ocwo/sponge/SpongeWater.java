package io.github.nagsfov.ocwo.sponge;

import com.mojang.serialization.Codec;
import io.github.nagsfov.ocwo.Ocwo;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipProvider;

import java.util.function.Consumer;

/** Water levels a sponge item carries (item component {@code ocwo:sponge_water}): only items at the same level stack. */
public record SpongeWater(int levels) implements TooltipProvider {

	public static final Codec<SpongeWater> CODEC = Codec.INT.xmap(SpongeWater::new, SpongeWater::levels);
	public static final StreamCodec<RegistryFriendlyByteBuf, SpongeWater> STREAM_CODEC = ByteBufCodecs.VAR_INT.<RegistryFriendlyByteBuf>cast()
			.map(SpongeWater::new, SpongeWater::levels);

	@Override
	public void addToTooltip(Item.TooltipContext context, Consumer<Component> consumer, TooltipFlag flag, DataComponentGetter components) {
		if (levels > 0) consumer.accept(Component.translatable("tooltip." + Ocwo.MOD_ID + ".sponge_water", levels));
	}
}
