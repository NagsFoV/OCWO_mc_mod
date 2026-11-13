package io.github.nagsfov.ocwo;

import io.github.nagsfov.ocwo.debug.DebugPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;

public class OcwoFabricClient implements ClientModInitializer {

	/** Lines of the debug panel sent by the server (/ocwo debug); empty = hidden. */
	private static volatile List<String> debugLines = List.of();

	@Override
	public void onInitializeClient() {
		Ocwo.initClient();
		ClientPlayNetworking.registerGlobalReceiver(DebugPayload.TYPE, (payload, context) -> debugLines = payload.lines());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> debugLines = List.of());
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(Ocwo.MOD_ID, "debug_panel"), (graphics, delta) -> drawDebug(graphics));
	}

	/** Panel at the top right of the screen, under nothing else, a translucent box behind the text. */
	private static void drawDebug(GuiGraphicsExtractor graphics) {
		List<String> lines = debugLines;
		if (lines.isEmpty()) return;
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		int width = 0;
		for (String l : lines) width = Math.max(width, font.width(Component.literal(l)));
		int lineH = font.lineHeight + 1;
		int x = graphics.guiWidth() - width - 6;
		int y = 4;
		graphics.fill(x - 3, y - 2, x + width + 3, y + lines.size() * lineH + 1, 0x90000000);
		for (String l : lines) {
			graphics.text(font, Component.literal(l), x, y, 0xFFFFFFFF, false);
			y += lineH;
		}
	}
}
