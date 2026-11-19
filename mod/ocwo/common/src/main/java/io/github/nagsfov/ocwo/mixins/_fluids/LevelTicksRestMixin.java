package io.github.nagsfov.ocwo.mixins._fluids;

import io.github.nagsfov.ocwo.fluids.WaterRest;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A block tick scheduled while the block changes belong to the world generation (end of the generation of a chunk,
 * or a block tick of it) belongs to it too: when it runs, its changes do not wake the water (see
 * WaterRest.takeGenerationTick). A pile of sand above a cave falls block after block, each fall scheduling the next.
 */
@Mixin(LevelTicks.class)
public abstract class LevelTicksRestMixin {

	@Inject(method = "schedule", at = @At("HEAD"))
	private void ocwo$generationTick(ScheduledTick<?> tick, CallbackInfo ci) {
		if (WaterRest.isSuppressed() && tick.type() instanceof Block) WaterRest.onGenerationTickScheduled(tick.pos(), tick.triggerTick());
	}
}
