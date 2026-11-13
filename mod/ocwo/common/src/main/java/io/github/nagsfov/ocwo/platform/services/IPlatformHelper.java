package io.github.nagsfov.ocwo.platform.services;

import com.mojang.brigadier.CommandDispatcher;
import io.github.nagsfov.ocwo.fluids.layer.FluidLayer;
import io.github.nagsfov.ocwo.moisture.MoistureLayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.component.TooltipProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.Nullable;

/**
 * Everything the common code needs from the mod loader.
 * Implemented once per loader (Fabric, NeoForge) and found through {@link java.util.ServiceLoader}.
 */
public interface IPlatformHelper {

	String getPlatformName();

	boolean isModLoaded(String modId);

	boolean isDevelopmentEnvironment();

	default String getEnvironmentName() {
		return isDevelopmentEnvironment() ? "development" : "production";
	}

	// ---- Game rules ----

	GameRule<Integer> registerIntRule(String id, int defaultValue, int min, int max);

	GameRule<Boolean> registerBooleanRule(String id, boolean defaultValue);

	GameRule<Double> registerDoubleRule(String id, double defaultValue, double min, double max);

	// ---- Fluid layer stored on chunks (saved with the chunk, optionally synced to clients) ----

	void registerFluidLayer(boolean syncToClients);

	@Nullable
	FluidLayer getFluidLayer(ChunkAccess chunk);

	FluidLayer getOrCreateFluidLayer(ChunkAccess chunk);

	/** Sets (or removes with null) the layer and returns the previous one. */
	@Nullable
	FluidLayer setFluidLayer(ChunkAccess chunk, @Nullable FluidLayer layer);

	// ---- Moisture layer stored on chunks (saved with the chunk) ----

	void registerMoistureLayer();

	@Nullable
	MoistureLayer getMoistureLayer(ChunkAccess chunk);

	MoistureLayer getOrCreateMoistureLayer(ChunkAccess chunk);

	// ---- Data and commands ----

	/** Registers a server data reload listener (runs on start and on /reload). */
	void registerServerReloadListener(Identifier id, PreparableReloadListener listener);

	/** Registers server commands. */
	void registerCommands(java.util.function.Consumer<CommandDispatcher<CommandSourceStack>> registrar);

	// ---- Debug panel ----

	/** Registers the debug panel packet (server to client) and, on the client, its display. */
	void registerDebugPayload();

	void sendDebug(net.minecraft.server.level.ServerPlayer player, io.github.nagsfov.ocwo.debug.DebugPayload payload);

	// ---- Blocks and fluids ----

	/** Whether fire can burn this block (loader flammability registry). */
	boolean isFlammable(BlockState state);

	/** Fluid temperature in Kelvin (water = 300, lava = 1300). */
	int getFluidTemperature(Fluid fluid);

	Component getFluidName(Fluid fluid);

	SoundEvent getFluidFillSound(Fluid fluid);

	SoundEvent getFluidEmptySound(Fluid fluid);

	// ---- Items ----

	/** Shows this data component in item tooltips. */
	void registerTooltipComponent(DataComponentType<? extends TooltipProvider> type);
}
