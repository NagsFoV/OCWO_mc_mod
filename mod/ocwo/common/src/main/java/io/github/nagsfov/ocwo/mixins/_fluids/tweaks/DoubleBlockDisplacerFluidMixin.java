package io.github.nagsfov.ocwo.mixins._fluids.tweaks;

import io.github.nagsfov.ocwo.fluids.FluidUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;


@Mixin({DoublePlantBlock.class, DoorBlock.class})
public class DoubleBlockDisplacerFluidMixin {

	/**
	 * Lets the upper half displace the fluid in its way.
	 * PhysEx changed the flags (3) of the setBlock call; since 26.x vanilla calls setBlockAndUpdate (= flags 3),
	 * so OCWO redirects it to setBlock with the same flags plus the displacement flag.
	 */
	@Redirect(method = "setPlacedBy", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"
	))
	boolean placeBlock(Level level, BlockPos pos, BlockState state) {
		return level.setBlock(pos, state, Block.UPDATE_ALL | FluidUtils.DISPLACE_FLAG);
	}
}
