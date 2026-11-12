package io.github.nagsfov.ocwo.mixins._fluids.tweaks;

import io.github.nagsfov.ocwo.fluids.FluidUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(FallingBlockEntity.class)
public class FallingBlockFluidMixin {

	/**
	 * Lets the landing block displace the fluid in its way.
	 * PhysEx changed the flags (3) of the setBlock call; since 26.x vanilla calls setBlockAndUpdate (= flags 3),
	 * so OCWO redirects it to setBlock with the same flags plus the displacement flag.
	 */
	@Redirect(method = "tick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"
	))
	boolean setBlockWithDisplacement(Level level, BlockPos pos, BlockState state) {
		return level.setBlock(pos, state, Block.UPDATE_ALL | FluidUtils.DISPLACE_FLAG);
	}
}
