package io.github.nagsfov.ocwo.mixins._fluids;

import io.github.nagsfov.ocwo.fluids.CustomFluidTicks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Moisture random ticks, for every chunk that ticks, next to vanilla's random ticks (see MoistureSimulation). */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMoistureMixin {

	@Inject(method = "tickChunk", at = @At("TAIL"))
	private void ocwo$moistureRandomTicks(LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
		ServerLevel level = (ServerLevel) (Object) this;
		long t0 = io.github.nagsfov.ocwo.util.OcwoProfiler.isProfiling(level) ? System.nanoTime() : 0L;
		CustomFluidTicks.get(level).moisture().randomTickChunk(chunk);
		if (t0 != 0L) io.github.nagsfov.ocwo.util.OcwoProfiler.addSection(level, "hook: moisture random ticks", System.nanoTime() - t0);
	}
}
