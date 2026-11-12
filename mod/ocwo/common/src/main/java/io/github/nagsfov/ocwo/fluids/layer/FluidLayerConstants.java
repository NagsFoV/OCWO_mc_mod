package io.github.nagsfov.ocwo.fluids.layer;

import com.mojang.serialization.Codec;
import io.github.nagsfov.ocwo.Ocwo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ExtraCodecs;

public final class FluidLayerConstants {

	public static final StreamCodec<FriendlyByteBuf, FluidLayer> STREAM_CODEC = StreamCodec.of(FluidLayer::writeToBuffer, FluidLayer::read);
	public static final Codec<FluidLayer> CODEC = ExtraCodecs.NBT.xmap(FluidLayer::fromNbt, FluidLayer::toNbt);
	public static final Identifier ID = Identifier.fromNamespaceAndPath(Ocwo.MOD_ID, "fluid_layer");

	private FluidLayerConstants() {
	}
}
