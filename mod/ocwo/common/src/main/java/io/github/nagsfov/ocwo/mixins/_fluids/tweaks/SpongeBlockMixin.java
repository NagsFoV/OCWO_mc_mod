package io.github.nagsfov.ocwo.mixins._fluids.tweaks;

import io.github.nagsfov.ocwo.sponge.SpongeLogic;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SpongeBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** OCWO sponges: the sponge stores the water it absorbs instead of destroying it (see SpongeLogic). */
@Mixin(SpongeBlock.class)
public class SpongeBlockMixin {

	@Inject(method = "tryAbsorbWater", at = @At("HEAD"), cancellable = true)
	void ocwoAbsorb(Level level, BlockPos pos, CallbackInfo ci) {
		if (level instanceof ServerLevel sl) SpongeLogic.absorb(sl, pos);
		ci.cancel();
	}
}
