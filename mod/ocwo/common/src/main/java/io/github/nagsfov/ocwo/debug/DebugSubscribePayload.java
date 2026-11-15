package io.github.nagsfov.ocwo.debug;

import io.github.nagsfov.ocwo.Ocwo;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Client to server: which OCWO groups of the F3 screen are shown right now ({@link #HEAD} | {@link #BLOCK} |
 * {@link #SIM}); 0 = none (F3 closed or no OCWO entry enabled). Sent only when it changes.
 */
public record DebugSubscribePayload(int mask) implements CustomPacketPayload {

	public static final int HEAD = 1;
	public static final int BLOCK = 2;
	public static final int SIM = 4;

	public static final Type<DebugSubscribePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Ocwo.MOD_ID, "debug_subscribe"));
	public static final StreamCodec<ByteBuf, DebugSubscribePayload> STREAM_CODEC = ByteBufCodecs.VAR_INT
			.map(DebugSubscribePayload::new, DebugSubscribePayload::mask);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
