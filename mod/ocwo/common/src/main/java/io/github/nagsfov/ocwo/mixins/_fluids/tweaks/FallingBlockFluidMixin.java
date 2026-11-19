package io.github.nagsfov.ocwo.mixins._fluids.tweaks;

import io.github.nagsfov.ocwo.fluids.FluidUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import io.github.nagsfov.ocwo.fluids.GenerationFall;
import io.github.nagsfov.ocwo.fluids.WaterRest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FallingBlockEntity.class)
public class FallingBlockFluidMixin implements GenerationFall {

	/** Started to fall during the end of the world generation (not saved: a fall lasts a few seconds). */
	@Unique
	private boolean ocwo$generation;

	@Override
	public boolean ocwo$isGeneration() {
		return ocwo$generation;
	}

	@Override
	public void ocwo$setGeneration(boolean generation) {
		ocwo$generation = generation;
	}

	/** A fall that starts while the block changes wake no water (first ticks of a new chunk) belongs to the generation. */
	@Inject(method = "fall", at = @At("RETURN"))
	private static void ocwo$generationFall(Level level, BlockPos pos, BlockState state, CallbackInfoReturnable<FallingBlockEntity> cir) {
		if (WaterRest.isSuppressed()) ((GenerationFall) cir.getReturnValue()).ocwo$setGeneration(true);
	}

	/**
	 * Lets the landing block displace the fluid in its way.
	 * PhysEx changed the flags (3) of the setBlock call; since 26.x vanilla calls setBlockAndUpdate (= flags 3),
	 * so OCWO redirects it to setBlock with the same flags plus the displacement flag.
	 */
	@Redirect(method = "tick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"
	))
	boolean setBlockWithDisplacement(Level level, BlockPos pos, BlockState state) {
		// the landing of a fall of the world generation does not wake the water: the water it displaces goes up its
		// column, at rest
		boolean generation = ocwo$generation;
		if (generation) WaterRest.suppress(true);
		try {
			return level.setBlock(pos, state, Block.UPDATE_ALL | FluidUtils.DISPLACE_FLAG);
		} finally {
			if (generation) WaterRest.suppress(false);
		}
	}
}
