package io.github.nagsfov.ocwo.gametest.mixin;

import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** OCWO tests: runs the pending tasks of the server thread (see GameTestServerTerrainMixin). */
@Mixin(BlockableEventLoop.class)
public interface EventLoopTasksAccess {

	@Invoker("runAllTasks")
	void ocwo$runAllTasks();
}
