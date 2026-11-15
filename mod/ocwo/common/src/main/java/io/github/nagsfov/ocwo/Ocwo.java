package io.github.nagsfov.ocwo;

import io.github.nagsfov.ocwo.fluids.OcwoFluidGameRules;
import io.github.nagsfov.ocwo.fluids.OcwoFluidItems;
import io.github.nagsfov.ocwo.fluids.VanillaFluidTweaks;
import io.github.nagsfov.ocwo.fluids.layer.FluidLayer;
import io.github.nagsfov.ocwo.moisture.MoistureCommand;
import io.github.nagsfov.ocwo.moisture.MoistureData;
import io.github.nagsfov.ocwo.platform.Services;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Overly Complicated Water Overhaul: common entry point, called by each loader module.
 * <p>
 * Fork of PhysEx-Reborn by Sasai_Kudasai_BM (GPLv3), modified.
 */
public final class Ocwo {

	public static final String MOD_ID = "ocwo";
	public static final String MOD_NAME = "Overly Complicated Water Overhaul";
	public static final Path CFG_DIR = Path.of("config", MOD_ID);
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

	private Ocwo() {
	}

	/** Called once by the loader entry point, during mod initialization. */
	public static void init() {
		LOGGER.info("Initializing {} on {}", MOD_NAME, Services.PLATFORM.getPlatformName());
		OcwoFluidGameRules.init();
		if (OcwoBootConfig.INSTANCE.isFluidPhysicsEnabled()) {
			VanillaFluidTweaks.init();
			if (OcwoBootConfig.INSTANCE.isExtraFluidLayerEnabled()) {
				FluidLayer.init();
			}
			// Block moisture (03-humidite-et-etats-des-blocs.md)
			Services.PLATFORM.registerMoistureLayer();
			Services.PLATFORM.registerServerReloadListener(MoistureData.RELOAD_ID, MoistureData.LISTENER);
			Services.PLATFORM.registerCommands(MoistureCommand::register);
			// OCWO lines of the F3 debug screen (/ocwo debug only points there)
			Services.PLATFORM.registerDebugPayload();
			Services.PLATFORM.registerCommands(io.github.nagsfov.ocwo.debug.OcwoDebug::register);
		}
		if (fluidItemsEnabled()) {
			OcwoFluidItems.init();
		}
	}

	/** Called by the loader client entry point. */
	public static void initClient() {
		if (OcwoBootConfig.INSTANCE.isServerOnly()) {
			throw new IllegalStateException(MOD_NAME + """
					 mod was configured for server-only mode but it presents on client side
					delete it from client or disable server-only mode
					"""
			);
		}
	}

	public static boolean fluidItemsEnabled() {
		return OcwoBootConfig.INSTANCE.isFluidPhysicsEnabled() && !OcwoBootConfig.INSTANCE.isServerOnly();
	}
}
