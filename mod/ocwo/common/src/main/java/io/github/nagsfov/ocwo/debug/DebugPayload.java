package io.github.nagsfov.ocwo.debug;

import io.github.nagsfov.ocwo.Ocwo;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Server to client: the lines of the OCWO debug panel (empty list = hide the panel).
 * Colour of a line: a leading "§x" code (Minecraft formatting codes).
 */
public record DebugPayload(List<String> lines) implements CustomPacketPayload {

	public static final Type<DebugPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Ocwo.MOD_ID, "debug"));
	public static final StreamCodec<ByteBuf, DebugPayload> STREAM_CODEC = ByteBufCodecs.STRING_UTF8
			.apply(ByteBufCodecs.list(64))
			.map(DebugPayload::new, DebugPayload::lines);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
