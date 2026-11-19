package io.github.nagsfov.ocwo.gametest.mixin;

import io.github.nagsfov.ocwo.fluids.WaterRest;
import net.minecraft.gametest.framework.GameTestInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * OCWO tests, generation measurement only (-Docwo.terrain=true): on real terrain the test structure is placed under
 * ground, sometimes next to the water of an aquifer. Placing it does not wake that water: the measurement sees the
 * world generation only, not the water flowing into the test area.
 */
@Mixin(GameTestInfo.class)
public abstract class GameTestInfoRestMixin {

	@Inject(method = "prepareTestStructure", at = @At("HEAD"))
	private void ocwo$restStart(CallbackInfoReturnable<GameTestInfo> cir) {
		if (Boolean.getBoolean("ocwo.terrain")) WaterRest.suppress(true);
	}

	@Inject(method = "prepareTestStructure", at = @At("RETURN"))
	private void ocwo$restEnd(CallbackInfoReturnable<GameTestInfo> cir) {
		if (Boolean.getBoolean("ocwo.terrain")) WaterRest.suppress(false);
	}
}
