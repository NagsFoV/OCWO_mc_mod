package io.github.nagsfov.ocwo.mixins._fluids;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import io.github.nagsfov.ocwo.fluids.FluidUtils;
import io.github.nagsfov.ocwo.fluids.OcwoFluidGameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Level.class)
public abstract class WorldFluidsMixin {

	@Shadow
	public abstract FluidState getFluidState(BlockPos blockPos);

	@Shadow
	public abstract boolean isClientSide();

	@SuppressWarnings("DataFlowIssue")
	@Redirect(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/world/level/chunk/LevelChunk;setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;"
			))
	public BlockState setBlock(LevelChunk chunk,
	                           BlockPos blockPos,
	                           BlockState blockState,
	                           int flags
	) {
		FluidState fs = null;
		boolean check = false;
		if (!isClientSide()) {
			if (FluidUtils.checkFlagsForDisplace(flags)) {
				ServerLevel world = (ServerLevel) (Object) this;
				int limit = world.getGameRules().get(OcwoFluidGameRules.FLUID_DISPLACEMENT_LIMIT);
				if (limit > 0) {
					fs = getFluidState(blockPos);
					if (!fs.isEmpty() && fs != blockState.getFluidState()) {
						check = true;
					}
				}
			}
			if ((flags & Block.UPDATE_NEIGHBORS) != 0) {
				FluidUtils.scheduleExtraUpdates((Level) (Object) this, blockPos);
			}
		}
		BlockState oldState = chunk.setBlockState(blockPos, blockState, flags);
		if (!isClientSide() && oldState != null) {
			io.github.nagsfov.ocwo.fluids.CustomFluidTicks ticks = io.github.nagsfov.ocwo.fluids.CustomFluidTicks.get((Level) (Object) this);
			ticks.moisture().onBlockChanged(blockPos, oldState, blockState);
			ticks.onBlockChanged(blockPos);
		}
		//noinspection ConstantValue
		if (check) {
			ServerLevel sl = (ServerLevel) (Object) this;
			long t0 = io.github.nagsfov.ocwo.util.OcwoProfiler.isProfiling(sl) ? System.nanoTime() : 0L;
			FluidUtils.displaceHook(sl, blockPos, oldState, blockState, blockState.getFluidState(), fs, flags);
			if (t0 != 0L) io.github.nagsfov.ocwo.util.OcwoProfiler.addNanos(sl, System.nanoTime() - t0);
		}
		return oldState;
	}

}
