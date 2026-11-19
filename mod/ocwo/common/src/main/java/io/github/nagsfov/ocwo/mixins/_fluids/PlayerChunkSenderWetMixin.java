package io.github.nagsfov.ocwo.mixins._fluids;

import io.github.nagsfov.ocwo.moisture.WetSync;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Right after a chunk is sent to a player: its visible blocks wetter than at their generation (see WetSync). */
@Mixin(PlayerChunkSender.class)
public abstract class PlayerChunkSenderWetMixin {

	@Inject(method = "sendChunk", at = @At("TAIL"))
	private static void ocwo$wetBlocks(ServerGamePacketListenerImpl connection, ServerLevel level, LevelChunk chunk, CallbackInfo ci) {
		WetSync.onChunkSent(connection.player, level, chunk);
	}
}
