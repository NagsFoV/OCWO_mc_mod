package io.github.nagsfov.ocwo.client.mixins._fluids._debug;

import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Adds the OCWO entries to the F3 debug screen (vanilla keeps its registration method private). */
@Mixin(DebugScreenEntries.class)
public interface DebugScreenEntriesInvoker {

	@Invoker("register")
	static Identifier ocwo$register(Identifier id, DebugScreenEntry entry) {
		throw new AssertionError();
	}
}
