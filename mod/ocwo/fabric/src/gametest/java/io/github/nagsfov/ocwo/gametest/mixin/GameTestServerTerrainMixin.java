package io.github.nagsfov.ocwo.gametest.mixin;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * OCWO tests: the test server builds a flat world. With -Docwo.terrain=true (run gameTestGeneration only), it builds a
 * normal world instead (real terrain, seed 0), to measure the cost of the world generation; and it waits between
 * ticks like a normal server (20 ticks per second) instead of running the next tick at once, so that the measurements
 * with and without OCWO see the same number of ticks.
 */
@Mixin(GameTestServer.class)
public abstract class GameTestServerTerrainMixin {

	@ModifyArg(method = "lambda$create$1",
			at = @At(value = "INVOKE",
					target = "Lnet/minecraft/core/HolderLookup$RegistryLookup;getOrThrow(Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/core/Holder$Reference;"))
	private static ResourceKey<WorldPreset> ocwo$terrain(ResourceKey<WorldPreset> preset) {
		return Boolean.getBoolean("ocwo.terrain") ? WorldPresets.NORMAL : preset;
	}

	@Inject(method = "waitUntilNextTick", at = @At("HEAD"), cancellable = true)
	private void ocwo$pace(CallbackInfo ci) {
		if (!Boolean.getBoolean("ocwo.terrain")) return;
		// as MinecraftServer.waitUntilNextTick (GameTestServer only runs the tasks)
		BlockableEventLoop<?> loop = (BlockableEventLoop<?>) (Object) this;
		MinecraftServerPaceAccess server = (MinecraftServerPaceAccess) this;
		((EventLoopTasksAccess) this).ocwo$runAllTasks();
		server.ocwo$setWaitingForNextTick(true);
		try {
			loop.managedBlock(() -> !server.ocwo$haveTime());
		} finally {
			server.ocwo$setWaitingForNextTick(false);
		}
		ci.cancel();
	}
}
