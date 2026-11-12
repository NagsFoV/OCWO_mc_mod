package io.github.nagsfov.ocwo.platform;

import io.github.nagsfov.ocwo.fluids.layer.FluidLayer;
import io.github.nagsfov.ocwo.moisture.MoistureLayer;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import io.github.nagsfov.ocwo.platform.services.IPlatformHelper;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.component.TooltipProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;

/** NeoForge platform layer: skeleton, to be implemented once the Fabric version is stable. */
public class NeoForgePlatformHelper implements IPlatformHelper {

	private static UnsupportedOperationException todo() {
		return new UnsupportedOperationException("OCWO: NeoForge support is not implemented yet");
	}

	@Override
	public String getPlatformName() {
		return "NeoForge";
	}

	@Override
	public boolean isModLoaded(String modId) {
		return ModList.get().isLoaded(modId);
	}

	@Override
	public boolean isDevelopmentEnvironment() {
		return !FMLEnvironment.isProduction();
	}

	@Override
	public GameRule<Integer> registerIntRule(String id, int defaultValue, int min, int max) {
		throw todo();
	}

	@Override
	public GameRule<Boolean> registerBooleanRule(String id, boolean defaultValue) {
		throw todo();
	}

	@Override
	public GameRule<Double> registerDoubleRule(String id, double defaultValue, double min, double max) {
		throw todo();
	}

	@Override
	public void registerFluidLayer(boolean syncToClients) {
		throw todo();
	}

	@Override
	public FluidLayer getFluidLayer(ChunkAccess chunk) {
		throw todo();
	}

	@Override
	public FluidLayer getOrCreateFluidLayer(ChunkAccess chunk) {
		throw todo();
	}

	@Override
	public FluidLayer setFluidLayer(ChunkAccess chunk, FluidLayer layer) {
		throw todo();
	}

	@Override
	public void registerMoistureLayer() {
		throw todo();
	}

	@Override
	public MoistureLayer getMoistureLayer(ChunkAccess chunk) {
		throw todo();
	}

	@Override
	public MoistureLayer getOrCreateMoistureLayer(ChunkAccess chunk) {
		throw todo();
	}

	@Override
	public void registerServerReloadListener(Identifier id, PreparableReloadListener listener) {
		throw todo();
	}

	@Override
	public void registerCommands(java.util.function.Consumer<CommandDispatcher<CommandSourceStack>> registrar) {
		throw todo();
	}

	@Override
	public boolean isFlammable(BlockState state) {
		throw todo();
	}

	@Override
	public int getFluidTemperature(Fluid fluid) {
		throw todo();
	}

	@Override
	public Component getFluidName(Fluid fluid) {
		throw todo();
	}

	@Override
	public SoundEvent getFluidFillSound(Fluid fluid) {
		throw todo();
	}

	@Override
	public SoundEvent getFluidEmptySound(Fluid fluid) {
		throw todo();
	}

	@Override
	public void registerTooltipComponent(DataComponentType<? extends TooltipProvider> type) {
		throw todo();
	}
}
