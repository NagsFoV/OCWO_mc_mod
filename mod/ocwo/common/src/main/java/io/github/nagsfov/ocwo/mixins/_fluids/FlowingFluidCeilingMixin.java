package io.github.nagsfov.ocwo.mixins._fluids;

import io.github.nagsfov.ocwo.fluids.FluidUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * OCWO: a full block of fluid (8/8) under a solid ceiling has full height (same rule as the renderer,
 * see FluidCeilingRenderMixin), so entities and boats see the same water as the player.
 */
@Mixin(FlowingFluid.class)
public class FlowingFluidCeilingMixin {

	@Inject(method = "getHeight(Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F",
			at = @At("HEAD"), cancellable = true)
	private void ocwo$fullUnderCeiling(FluidState fluidState, BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
		if (fluidState.getAmount() == FluidUtils.MAX_LEVEL && FluidUtils.isSolidCeiling(level, pos.above())) {
			cir.setReturnValue(1.0F);
		}
	}
}
