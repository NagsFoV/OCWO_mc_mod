package io.github.nagsfov.ocwo.client.mixins._fluids;

import io.github.nagsfov.ocwo.fluids.FluidUtils;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * OCWO: a full block of fluid (8/8) under a solid ceiling is drawn at full height (vanilla: 8/9 of a block
 * unless the same fluid is above).
 */
@Mixin(FluidRenderer.class)
public class FluidCeilingRenderMixin {

	@Inject(method = "getHeight(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/world/level/material/Fluid;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)F",
			at = @At("HEAD"), cancellable = true)
	private void ocwo$fullUnderCeiling(BlockAndTintGetter level, Fluid fluidType, BlockPos pos, BlockState state, FluidState fluidState,
	                                   CallbackInfoReturnable<Float> cir) {
		FluidState fs = level.getFluidState(pos);
		if (fluidType.isSame(fs.getType()) && fs.getAmount() == FluidUtils.MAX_LEVEL && FluidUtils.isSolidCeiling(level, pos.above())) {
			cir.setReturnValue(1.0F);
		}
	}
}
