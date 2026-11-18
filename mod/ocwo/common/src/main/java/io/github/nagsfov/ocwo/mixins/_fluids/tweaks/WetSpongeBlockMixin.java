package io.github.nagsfov.ocwo.mixins._fluids.tweaks;

import io.github.nagsfov.ocwo.sponge.SpongeLogic;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WetSpongeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.Orientation;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** OCWO sponges: a wet sponge is a sponge that holds water; it keeps absorbing until full (see SpongeLogic). */
@Mixin(WetSpongeBlock.class)
public abstract class WetSpongeBlockMixin extends Block {

	public WetSpongeBlockMixin(Properties properties) {
		super(properties);
	}

	@Inject(method = "onPlace", at = @At("HEAD"), cancellable = true)
	void ocwoOnPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston, CallbackInfo ci) {
		if (level instanceof ServerLevel sl) {
			if (level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, pos)) SpongeLogic.evaporate(sl, pos);
			else if (!oldState.is(state.getBlock())) SpongeLogic.absorb(sl, pos);
		}
		ci.cancel();
	}

	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, @Nullable Orientation orientation, boolean movedByPiston) {
		if (level instanceof ServerLevel sl) SpongeLogic.absorb(sl, pos);
		super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
	}
}
