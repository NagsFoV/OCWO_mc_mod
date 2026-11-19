package io.github.nagsfov.ocwo.moisture;

import io.github.nagsfov.ocwo.Ocwo;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Arrays;

/**
 * Server to client: how much wetter than at their generation the visible blocks of one chunk are (see {@link WetSync}),
 * for the darkening of wet blocks. {@code full}: every such block of the chunk (sent with the chunk; the client forgets
 * what it had for that chunk); otherwise only the blocks that changed (0 = back to its generation look). Each entry is
 * {@link #pack}ed: section Y, cell in the section, darkening step (0 to 3).
 */
public record WetBlocksPayload(int chunkX, int chunkZ, boolean full, int[] entries) implements CustomPacketPayload {

	public static final Type<WetBlocksPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Ocwo.MOD_ID, "wet_blocks"));
	/** At most this many entries in one packet (a whole section of visible wet blocks fits). */
	public static final int MAX_ENTRIES = 1 << 16;

	public static final StreamCodec<ByteBuf, WetBlocksPayload> STREAM_CODEC = StreamCodec.of(
			(buf, p) -> {
				VarInt.write(buf, p.chunkX);
				VarInt.write(buf, p.chunkZ);
				buf.writeBoolean(p.full);
				VarInt.write(buf, p.entries.length);
				for (int e : p.entries) VarInt.write(buf, e);
			},
			buf -> {
				int x = VarInt.read(buf), z = VarInt.read(buf);
				boolean full = buf.readBoolean();
				int n = VarInt.read(buf);
				if (n < 0 || n > MAX_ENTRIES) throw new IllegalArgumentException("wet blocks: " + n + " entries");
				int[] entries = new int[n];
				for (int i = 0; i < n; i++) entries[i] = VarInt.read(buf);
				return new WetBlocksPayload(x, z, full, entries);
			});

	/** One entry: section Y (-2048 to 2047), cell (y << 8 | z << 4 | x in the section), darkening step (0 to 3). */
	public static int pack(int sectionY, int cell, int step) {
		return ((sectionY + 2048) << 14) | (cell << 2) | (step & 3);
	}

	public static int sectionY(int entry) {
		return (entry >>> 14) - 2048;
	}

	public static int cell(int entry) {
		return (entry >>> 2) & 4095;
	}

	public static int step(int entry) {
		return entry & 3;
	}

	/** Same content (arrays compared by content, for the tests). */
	public boolean sameAs(WetBlocksPayload o) {
		return chunkX == o.chunkX && chunkZ == o.chunkZ && full == o.full && Arrays.equals(entries, o.entries);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
