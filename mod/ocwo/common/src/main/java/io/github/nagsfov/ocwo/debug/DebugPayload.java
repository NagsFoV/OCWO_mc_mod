package io.github.nagsfov.ocwo.debug;

import io.github.nagsfov.ocwo.Ocwo;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Server to client: the OCWO lines of the F3 debug screen, in four groups (water at head, block looked at,
 * simulation, reservoirs). Each line is a translation key followed by its arguments, separated by {@link #SEP}; an argument
 * starting with {@link #KEY} is itself a translation key. The client translates them (fr_fr, en_us...).
 */
public record DebugPayload(List<String> head, List<String> block, List<String> sim, List<String> reservoirs) implements CustomPacketPayload {

	public static final char SEP = '\u001f';
	public static final String KEY = "#";

	public static final Type<DebugPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Ocwo.MOD_ID, "debug"));
	private static final StreamCodec<ByteBuf, List<String>> LINES = ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(64));
	public static final StreamCodec<ByteBuf, DebugPayload> STREAM_CODEC = StreamCodec.composite(
			LINES, DebugPayload::head, LINES, DebugPayload::block, LINES, DebugPayload::sim, LINES, DebugPayload::reservoirs,
			DebugPayload::new);

	public static final DebugPayload EMPTY = new DebugPayload(List.of(), List.of(), List.of(), List.of());

	/** One line: translation key and arguments (strings, numbers already formatted, or {@link #key} references). */
	public static String line(String key, Object... args) {
		StringBuilder sb = new StringBuilder(key);
		for (Object a : args) sb.append(SEP).append(a);
		return sb.toString();
	}

	/** An argument translated by the client. */
	public static String key(String key) {
		return KEY + key;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
