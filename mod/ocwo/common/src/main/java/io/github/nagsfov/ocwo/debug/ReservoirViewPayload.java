package io.github.nagsfov.ocwo.debug;

import io.github.nagsfov.ocwo.Ocwo;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Arrays;

/**
 * Server to client, only while the player turned the display on ({@code /ocwo debug reservoirs}): the reservoirs
 * around the player, as packed block positions. {@code surface}: surface blocks of the abstract reservoirs (lakes and
 * seas that pay out of their surface); {@code sources}: the zone sources (outlets) recorded nearby; {@code saturated}:
 * the saturated wet ground that feeds them. An empty payload turns the display off.
 */
public record ReservoirViewPayload(long[] surface, long[] sources, long[] saturated) implements CustomPacketPayload {

	public static final Type<ReservoirViewPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Ocwo.MOD_ID, "reservoir_view"));
	public static final StreamCodec<ByteBuf, ReservoirViewPayload> STREAM_CODEC = StreamCodec.composite(
			ByteBufCodecs.LONG_ARRAY, ReservoirViewPayload::surface,
			ByteBufCodecs.LONG_ARRAY, ReservoirViewPayload::sources,
			ByteBufCodecs.LONG_ARRAY, ReservoirViewPayload::saturated,
			ReservoirViewPayload::new);

	public static final ReservoirViewPayload EMPTY = new ReservoirViewPayload(new long[0], new long[0], new long[0]);

	public boolean isEmpty() {
		return surface.length == 0 && sources.length == 0 && saturated.length == 0;
	}

	/** Same positions (arrays are compared by content, for the tests). */
	public boolean sameAs(ReservoirViewPayload o) {
		return Arrays.equals(surface, o.surface) && Arrays.equals(sources, o.sources) && Arrays.equals(saturated, o.saturated);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
