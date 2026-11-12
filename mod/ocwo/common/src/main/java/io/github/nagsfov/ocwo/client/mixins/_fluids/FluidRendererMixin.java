package io.github.nagsfov.ocwo.client.mixins._fluids;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.material.Fluid;
import io.github.nagsfov.ocwo.client.fluids.ClientFluidUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(FluidRenderer.class)
public class FluidRendererMixin {

	/*
	@Redirect(method = "tesselate",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/block/FluidRenderer;getHeight(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/world/level/material/Fluid;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)F"
			),
			slice = @Slice(
					from = @At(value = "INVOKE",
							target = "Lnet/minecraft/client/renderer/block/FluidRenderer;getHeight(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/world/level/material/Fluid;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)F",
							ordinal = 1
					),
					to = @At(value = "INVOKE",
							target = "Lnet/minecraft/client/renderer/block/FluidRenderer;calculateAverageHeight(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/world/level/material/Fluid;FFFLnet/minecraft/core/BlockPos;)F"
					))
	)
	float tess0(FluidRenderer instance,
				BlockAndTintGetter blockAndTintGetter,
				Fluid fluid,
				BlockPos pos2,
				BlockState blockState,
				FluidState fluidState
	) {
		return 0;
	}
	 */

	@Redirect(method = "tesselate",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/client/renderer/block/FluidRenderer;calculateAverageHeight(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/world/level/material/Fluid;FFFLnet/minecraft/core/BlockPos;)F"
			)
	)
	float calculateAverageHeight(FluidRenderer instance,
								 BlockAndTintGetter world,
								 Fluid fluid,
								 float self,
								 float g, float h,
								 BlockPos offsetPos,
								 @Local(argsOnly = true, type = BlockPos.class) BlockPos startPos
								 //@Local(argsOnly = true, type = BlockState.class) BlockState blockState,
								 //@Local(argsOnly = true, type = FluidState.class) FluidState fluidState
	) {
		return ClientFluidUtils.calculateAverageHeight(world, fluid, self, g, h, offsetPos, startPos);//, blockState, fluidState);
	}

}
