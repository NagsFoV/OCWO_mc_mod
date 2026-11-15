package io.github.nagsfov.ocwo.mixins._fluids;

import io.github.nagsfov.ocwo.fluids.WaterRest;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The last step of the world generation, when a chunk starts ticking (bubble columns, block shapes...), changes
 * blocks next to the generated water: that is not a disturbance, the water stays at rest (see WaterRest).
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkRestMixin {

	@Inject(method = "postProcessGeneration", at = @At("HEAD"))
	private void ocwo$restStart(ServerLevel level, CallbackInfo ci) {
		WaterRest.suppress(true);
	}

	@Inject(method = "postProcessGeneration", at = @At("RETURN"))
	private void ocwo$restEnd(ServerLevel level, CallbackInfo ci) {
		WaterRest.suppress(false);
	}
}
