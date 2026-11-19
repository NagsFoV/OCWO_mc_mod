package io.github.nagsfov.ocwo.client.mixins._fluids._wet;

import com.mojang.blaze3d.vertex.QuadInstance;
import io.github.nagsfov.ocwo.client.moisture.ClientWetness;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Darkening of wet blocks, vanilla meshing (see ClientWetness): the block's multiplier is looked up once per block,
 * then each of its quads gets its colour scaled after its light and before its tint. Without Sodium only (Sodium
 * builds the meshes itself: SodiumBlockRendererWetMixin).
 */
@Mixin(ModelBlockRenderer.class)
public abstract class ModelBlockRendererWetMixin {

	@Shadow
	@Final
	private QuadInstance quadInstance;

	/** Multiplier of the block being meshed (1 = as generated). */
	@Unique
	private float ocwo$wet = 1.0F;

	@Inject(method = "tesselateBlock", at = @At("HEAD"))
	private void ocwo$wetBlock(BlockQuadOutput output, float x, float y, float z, BlockAndTintGetter level, BlockPos pos, BlockState blockState,
	                           BlockStateModel model, long seed, CallbackInfo ci) {
		ocwo$wet = ClientWetness.factor(pos);
	}

	@Inject(method = "putQuadWithTint", at = @At("HEAD"))
	private void ocwo$wetQuad(BlockQuadOutput output, float x, float y, float z, BlockAndTintGetter level, BlockState state, BlockPos pos,
	                          BakedQuad quad, CallbackInfo ci) {
		if (ocwo$wet < 1.0F) quadInstance.scaleColor(ocwo$wet);
	}
}
