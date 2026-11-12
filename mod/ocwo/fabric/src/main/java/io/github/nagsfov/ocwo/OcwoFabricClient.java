package io.github.nagsfov.ocwo;

import net.fabricmc.api.ClientModInitializer;

public class OcwoFabricClient implements ClientModInitializer {

	@Override
	public void onInitializeClient() {
		Ocwo.initClient();
	}
}
