package io.github.nagsfov.ocwo;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Boot config (config/ocwo/boot.jsonc), read once at startup.
 * <p>
 * PhysEx read and wrote it with skdslib; OCWO uses Gson (shipped with Minecraft) and writes the comments itself.
 */
public final class OcwoBootConfig {
	public static final Path PATH = Ocwo.CFG_DIR.resolve("boot.jsonc");
	public static final OcwoBootConfig INSTANCE = load();

	private static final int VERSION = 1;
	private static final String COMMENT = """
			/*
			\t+------------------------------------------+
			\t| Not-reloadable config with major tweaks  |
			\t| restart is required for changes to apply |
			\t+------------------------------------------+
			*/
			""";

	/** Enables the fluids part. */
	private boolean fluidPhysicsEnabled = true;

	/** Adds new logic fluids layer to chunks (may cause stability issues). */
	private boolean extraFluidLayerEnabled = true;

	/** Enables the blocks part (WIP). Not saved, always false. */
	private final boolean blockPhysicsEnabled = false;

	/** Makes this mod server side only. */
	private boolean serverOnly = false;

	/** The flag that tells a placed block to try to displace fluid from it (FLAG = 1 << n). */
	private int fluidDisplaceFlagBitOffset = 23;

	private int configVersion = 0;

	public boolean isFluidPhysicsEnabled() {
		return fluidPhysicsEnabled;
	}

	public boolean isExtraFluidLayerEnabled() {
		return fluidPhysicsEnabled && extraFluidLayerEnabled;
	}

	public boolean isBlockPhysicsEnabled() {
		return blockPhysicsEnabled;
	}

	public boolean isServerOnly() {
		return serverOnly;
	}

	public int getFluidDisplaceFlagBitOffset() {
		return fluidDisplaceFlagBitOffset;
	}

	public enum WaterlogPolicy {
		ALL_OR_NOTHING,
		FILL_AT_HALF,
		FILL_ALWAYS
	}

	private static OcwoBootConfig load() {
		try {
			if (Files.exists(PATH)) {
				OcwoBootConfig cfg = parse(Files.readString(PATH));
				if (cfg.fluidDisplaceFlagBitOffset < 10 || cfg.fluidDisplaceFlagBitOffset > 31)
					throw new IllegalArgumentException("fluidDisplaceFlagBitOffset is out of bounds [10;31]");
				if (cfg.configVersion != VERSION) {
					cfg.configVersion = VERSION;
					save(cfg);
				}
				return cfg;
			}
		} catch (Exception e) {
			e.printStackTrace(System.err);
		}
		OcwoBootConfig cfg = new OcwoBootConfig();
		cfg.configVersion = VERSION;
		save(cfg);
		return cfg;
	}

	private static OcwoBootConfig parse(String text) {
		JsonReader reader = new JsonReader(new StringReader(text));
		reader.setStrictness(Strictness.LENIENT); // accepts the /* */ and // comments of the .jsonc file
		JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
		OcwoBootConfig cfg = new OcwoBootConfig();
		if (json.has("fluidPhysicsEnabled")) cfg.fluidPhysicsEnabled = json.get("fluidPhysicsEnabled").getAsBoolean();
		if (json.has("extraFluidLayerEnabled")) cfg.extraFluidLayerEnabled = json.get("extraFluidLayerEnabled").getAsBoolean();
		if (json.has("serverOnly")) cfg.serverOnly = json.get("serverOnly").getAsBoolean();
		if (json.has("fluidDisplaceFlagBitOffset")) cfg.fluidDisplaceFlagBitOffset = json.get("fluidDisplaceFlagBitOffset").getAsInt();
		if (json.has("configVersion")) cfg.configVersion = json.get("configVersion").getAsInt();
		return cfg;
	}

	private static void save(OcwoBootConfig cfg) {
		String text = COMMENT + """
				{
					// Enables the fluids part
					"fluidPhysicsEnabled": %s,

					// Adds new logic fluids layer to chunks (may cause stability issues)
					// Allows variety of hollow blocks to be filled with fluids
					// f.e. doors, anvils, chests, grass, fences
					"extraFluidLayerEnabled": %s,

					// Makes this mod server side only
					"serverOnly": %s,

					// !!! FOR ADVANCED USERS AND MOD MAKERS !!! The flag that telling placed block to try to displace fluid from it (FLAG = 1 << n)
					"fluidDisplaceFlagBitOffset": %d,

					// !!! Do not touch !!! (not for humans)
					"configVersion": %d
				}
				""".formatted(cfg.fluidPhysicsEnabled, cfg.extraFluidLayerEnabled, cfg.serverOnly,
				cfg.fluidDisplaceFlagBitOffset, cfg.configVersion);
		try {
			Files.createDirectories(PATH.getParent());
			Files.writeString(PATH, text, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		} catch (Exception e) {
			e.printStackTrace(System.err);
		}
	}
}
