package io.github.nagsfov.ocwo;

import io.github.nagsfov.ocwo.client.debug.OcwoDebugEntries;
import io.github.nagsfov.ocwo.client.debug.ReservoirView;
import io.github.nagsfov.ocwo.client.moisture.ClientWetness;
import io.github.nagsfov.ocwo.debug.DebugPayload;
import io.github.nagsfov.ocwo.debug.DebugSubscribePayload;
import io.github.nagsfov.ocwo.debug.ReservoirViewPayload;
import io.github.nagsfov.ocwo.moisture.WetBlocksPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class OcwoFabricClient implements ClientModInitializer {

	/** Groups the server was last told about (see DebugSubscribePayload). */
	private static int sentMask;

	@Override
	public void onInitializeClient() {
		Ocwo.initClient();
		if (!OcwoBootConfig.INSTANCE.isFluidPhysicsEnabled()) return;
		// OCWO lines of the F3 debug screen
		OcwoDebugEntries.register();
		ClientPlayNetworking.registerGlobalReceiver(DebugPayload.TYPE, (payload, context) -> OcwoDebugEntries.receive(payload));
		// display of the reservoirs in the world (/ocwo debug reservoirs)
		ClientPlayNetworking.registerGlobalReceiver(ReservoirViewPayload.TYPE, (payload, context) -> ReservoirView.receive(payload));
		// darkening of wet blocks: the steps sent by the server, forgotten with their chunk
		ClientPlayNetworking.registerGlobalReceiver(WetBlocksPayload.TYPE, (payload, context) -> ClientWetness.receive(payload));
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents.CHUNK_UNLOAD.register(
				(level, chunk) -> ClientWetness.dropChunk(chunk.getPos().x(), chunk.getPos().z()));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			sentMask = 0;
			OcwoDebugEntries.reset();
			ReservoirView.reset();
			ClientWetness.clear();
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			sentMask = 0;
			OcwoDebugEntries.reset();
			ReservoirView.reset();
			ClientWetness.clear();
		});
		ClientTickEvents.END_CLIENT_TICK.register(ReservoirView::tick);
		// ask the server for the lines only while F3 shows an OCWO entry
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			int mask = OcwoDebugEntries.currentMask(client);
			if (mask == sentMask || !ClientPlayNetworking.canSend(DebugSubscribePayload.TYPE)) return;
			sentMask = mask;
			if (mask == 0) OcwoDebugEntries.reset();
			ClientPlayNetworking.send(new DebugSubscribePayload(mask));
		});
	}
}
