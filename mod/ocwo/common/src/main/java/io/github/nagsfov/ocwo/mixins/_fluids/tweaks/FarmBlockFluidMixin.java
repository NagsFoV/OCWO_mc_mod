package io.github.nagsfov.ocwo.mixins._fluids.tweaks;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import io.github.nagsfov.ocwo.fluids.FluidUtils;
import io.github.nagsfov.ocwo.fluids.OcwoFluidGameRules;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FarmlandBlock.class)
public class FarmBlockFluidMixin {

	@Inject(method = "randomTick", at = @At("HEAD"), cancellable = true, order = 2001)
	void randomTick(BlockState blockState, ServerLevel serverLevel, BlockPos blockPos, RandomSource randomSource, CallbackInfo ci) {
		double intake = serverLevel.getGameRules().get(OcwoFluidGameRules.FARMLAND_WATER_INTAKE);
		if (intake < 0) return;
		int i = blockState.getValue(FarmlandBlock.MOISTURE);
		int dry = FarmlandBlock.MAX_MOISTURE - i;
		if (!serverLevel.isRainingAt(blockPos.above()) &&
				!FluidUtils.farmlandConsumeWater(blockState, serverLevel, blockPos, randomSource, dry + 1, intake)
		) {
			if (i > 0) {
				serverLevel.setBlock(blockPos, blockState.setValue(FarmlandBlock.MOISTURE, i - 1), 2);
			} else if (!shouldMaintainFarmland(serverLevel, blockPos)) {
				turnToBaseBlock(null, blockState, serverLevel, blockPos);
			}
		} else if (i < 7) {
			serverLevel.setBlock(blockPos, blockState.setValue(FarmlandBlock.MOISTURE, 7), 2);
		}
		ci.cancel();
	}

	// 26.x: vanilla turnToDirt (static) became turnToBaseBlock (instance method)
	@Shadow
	public void turnToBaseBlock(@Nullable Entity entity, BlockState blockState, Level level, BlockPos blockPos) {
	}

	@Shadow
	private static boolean shouldMaintainFarmland(BlockGetter blockGetter, BlockPos blockPos) {
		return false;
	}

	@Shadow
	private static boolean isNearWater(LevelReader levelReader, BlockPos blockPos) {
		return false;
	}
}
