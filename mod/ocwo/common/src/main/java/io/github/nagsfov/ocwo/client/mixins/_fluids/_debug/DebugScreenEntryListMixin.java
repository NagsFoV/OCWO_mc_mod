package io.github.nagsfov.ocwo.client.mixins._fluids._debug;

import io.github.nagsfov.ocwo.client.debug.OcwoDebugEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryList;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Default visibility of the OCWO entries: water at head and block looked at are shown with F3; the simulation
 * entry is off until turned on in F3+F6. A choice saved by the player (any status) is kept.
 */
@Mixin(DebugScreenEntryList.class)
public class DebugScreenEntryListMixin {

	@Shadow
	@Final
	private Map<Identifier, DebugScreenEntryStatus> allStatuses;

	@Inject(method = "resetStatuses", at = @At("TAIL"))
	private void ocwo$defaults(Map<Identifier, DebugScreenEntryStatus> newEntries, CallbackInfo ci) {
		allStatuses.putIfAbsent(OcwoDebugEntries.HEAD, DebugScreenEntryStatus.IN_OVERLAY);
		allStatuses.putIfAbsent(OcwoDebugEntries.BLOCK, DebugScreenEntryStatus.IN_OVERLAY);
	}
}
