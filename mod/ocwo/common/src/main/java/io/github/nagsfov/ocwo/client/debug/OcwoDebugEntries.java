package io.github.nagsfov.ocwo.client.debug;

import io.github.nagsfov.ocwo.Ocwo;
import io.github.nagsfov.ocwo.client.mixins._fluids._debug.DebugScreenEntriesInvoker;
import io.github.nagsfov.ocwo.debug.DebugPayload;
import io.github.nagsfov.ocwo.debug.DebugSubscribePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The four OCWO entries of the F3 debug screen (listed in F3+F6 as ocwo_water_at_head, ocwo_looking_at,
 * ocwo_simulation and ocwo_reservoirs). Their lines are computed by the server
 * ({@link io.github.nagsfov.ocwo.debug.OcwoDebug}) and translated here.
 */
public final class OcwoDebugEntries {

	public static final Identifier HEAD = Identifier.fromNamespaceAndPath(Ocwo.MOD_ID, "ocwo_water_at_head");
	public static final Identifier BLOCK = Identifier.fromNamespaceAndPath(Ocwo.MOD_ID, "ocwo_looking_at");
	public static final Identifier SIM = Identifier.fromNamespaceAndPath(Ocwo.MOD_ID, "ocwo_simulation");
	public static final Identifier RESERVOIRS = Identifier.fromNamespaceAndPath(Ocwo.MOD_ID, "ocwo_reservoirs");

	/** Last lines received from the server. */
	private static volatile DebugPayload latest = DebugPayload.EMPTY;
	private static boolean registered;

	private OcwoDebugEntries() {
	}

	public static void register() {
		if (registered) return;
		registered = true;
		DebugScreenEntriesInvoker.ocwo$register(HEAD, new Entry(HEAD, DebugPayload::head));
		DebugScreenEntriesInvoker.ocwo$register(BLOCK, new Entry(BLOCK, DebugPayload::block));
		DebugScreenEntriesInvoker.ocwo$register(SIM, new Entry(SIM, DebugPayload::sim));
		DebugScreenEntriesInvoker.ocwo$register(RESERVOIRS, new Entry(RESERVOIRS, DebugPayload::reservoirs));
	}

	public static void receive(DebugPayload payload) {
		latest = payload;
	}

	public static void reset() {
		latest = DebugPayload.EMPTY;
	}

	/** Groups currently shown by F3 (0 when none, or when reduced debug info is on). */
	public static int currentMask(Minecraft mc) {
		if (mc.player == null || mc.showOnlyReducedInfo()) return 0;
		var enabled = mc.debugEntries.getCurrentlyEnabled();
		int mask = 0;
		if (enabled.contains(HEAD)) mask |= DebugSubscribePayload.HEAD;
		if (enabled.contains(BLOCK)) mask |= DebugSubscribePayload.BLOCK;
		if (enabled.contains(SIM)) mask |= DebugSubscribePayload.SIM;
		if (enabled.contains(RESERVOIRS)) mask |= DebugSubscribePayload.RESERVOIRS;
		return mask;
	}

	/** Translates one line of the payload: key, then arguments ({@link DebugPayload#KEY} = translated too). */
	public static String translate(String encoded) {
		String[] parts = encoded.split(String.valueOf(DebugPayload.SEP), -1);
		Object[] args = new Object[parts.length - 1];
		for (int i = 1; i < parts.length; i++) args[i - 1] = translateArg(parts[i]);
		return I18n.get(parts[0], args);
	}

	private static String translateArg(String a) {
		if (!a.startsWith(DebugPayload.KEY)) return a;
		if (!a.startsWith(DebugPayload.KEY + "ocwo.debug.flag.")) return I18n.get(a.substring(1));
		// flags: "#key1+#key2" become " (falling, fluid layer)"
		List<String> out = new ArrayList<>();
		for (String k : a.split("\\+")) out.add(I18n.get(k.substring(DebugPayload.KEY.length())));
		return " (" + String.join(", ", out) + ")";
	}

	private record Entry(Identifier id, Function<DebugPayload, List<String>> group) implements DebugScreenEntry {
		@Override
		public void display(DebugScreenDisplayer displayer, @Nullable Level level, @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk) {
			List<String> lines = group.apply(latest);
			if (lines.isEmpty()) return;
			List<String> out = new ArrayList<>(lines.size());
			for (String l : lines) out.add(translate(l));
			displayer.addToGroup(id, out);
		}
	}
}
