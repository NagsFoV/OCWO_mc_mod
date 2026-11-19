package io.github.nagsfov.ocwo.client.mixins._fluids._wet;

import io.github.nagsfov.ocwo.client.moisture.ClientWetness;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Darkening of wet blocks with Sodium (written against Sodium 0.9.2 for 26.3, without depending on it: applied only
 * when Sodium is there, and skipped if its code changed). Sodium builds the chunk meshes itself: the block's multiplier
 * is looked up in renderModel, then the brightness of each vertex of its quads is scaled in bufferQuad (Sodium
 * multiplies the vertex colour by it).
 */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer", remap = false)
public abstract class SodiumBlockRendererWetMixin {

	/** Multiplier of the block being meshed (1 = as generated). */
	@Unique
	private float ocwo$wet = 1.0F;

	@Inject(method = "renderModel", at = @At("HEAD"), require = 0, remap = false)
	private void ocwo$wetBlock(BlockStateModel model, BlockState state, BlockPos pos, BlockPos origin, CallbackInfo ci) {
		ocwo$wet = ClientWetness.factor(pos);
	}

	@Inject(method = "bufferQuad", at = @At("HEAD"), require = 0, remap = false)
	private void ocwo$wetQuad(@Coerce Object quad, float[] brightnesses, @Coerce Object material, CallbackInfo ci) {
		if (ocwo$wet >= 1.0F) return;
		for (int i = 0; i < brightnesses.length; i++) brightnesses[i] *= ocwo$wet;
	}
}
