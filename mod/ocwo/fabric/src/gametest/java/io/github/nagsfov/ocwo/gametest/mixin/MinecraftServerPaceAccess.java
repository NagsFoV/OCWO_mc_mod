package io.github.nagsfov.ocwo.gametest.mixin;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** OCWO tests: what the generation measurement needs to wait between ticks like a normal server. */
@Mixin(MinecraftServer.class)
public interface MinecraftServerPaceAccess {

	@Invoker("haveTime")
	boolean ocwo$haveTime();

	@Accessor("waitingForNextTick")
	void ocwo$setWaitingForNextTick(boolean waiting);
}
