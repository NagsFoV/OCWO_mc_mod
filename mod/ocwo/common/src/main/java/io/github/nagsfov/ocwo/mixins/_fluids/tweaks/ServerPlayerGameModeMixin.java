package io.github.nagsfov.ocwo.mixins._fluids.tweaks;

import io.github.nagsfov.ocwo.sponge.SpongeLogic;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * OCWO: water is conserved in survival and adventure only. A player in creative mode (who drops nothing when breaking
 * a block) may create and destroy water: what the blocks broken by such a player held disappears.
 */
@Mixin(ServerPlayerGameMode.class)
public class ServerPlayerGameModeMixin {

	@Shadow
	@Final
	protected ServerPlayer player;

	@Shadow
	protected ServerLevel level;

	@Inject(method = "destroyBlock", at = @At("HEAD"))
	void ocwoCreativeBreakStart(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (player.preventsBlockDrops()) SpongeLogic.creativeBreak(level, pos, true);
	}

	@Inject(method = "destroyBlock", at = @At("RETURN"))
	void ocwoCreativeBreakEnd(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		SpongeLogic.creativeBreak(level, pos, false);
	}
}
