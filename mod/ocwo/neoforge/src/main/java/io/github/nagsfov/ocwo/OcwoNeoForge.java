package io.github.nagsfov.ocwo;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

/**
 * NeoForge entry point. Skeleton only for now: the NeoForge platform layer is not implemented yet,
 * so the mod does not initialize on NeoForge.
 */
@Mod(Ocwo.MOD_ID)
public class OcwoNeoForge {

	public OcwoNeoForge(IEventBus eventBus) {
		Ocwo.LOGGER.warn("{}: NeoForge support is not implemented yet, the mod is inactive.", Ocwo.MOD_NAME);
	}
}
