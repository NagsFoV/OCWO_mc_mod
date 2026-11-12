package io.github.nagsfov.ocwo;

import net.fabricmc.api.ModInitializer;

public class OcwoFabric implements ModInitializer {

	@Override
	public void onInitialize() {
		Ocwo.init();
	}
}
