package io.github.nagsfov.ocwo.platform;

import io.github.nagsfov.ocwo.Ocwo;
import io.github.nagsfov.ocwo.fluids.layer.FluidLayer;
import io.github.nagsfov.ocwo.fluids.layer.FluidLayerConstants;
import io.github.nagsfov.ocwo.moisture.MoistureLayer;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import io.github.nagsfov.ocwo.platform.services.IPlatformHelper;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.fabricmc.fabric.api.item.v1.ItemComponentTooltipProviderRegistry;
import net.fabricmc.fabric.api.registry.FlammableBlockRegistry;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariantAttributes;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.component.TooltipProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import net.minecraft.world.level.material.Fluid;

public class FabricPlatformHelper implements IPlatformHelper {

	private static AttachmentType<FluidLayer> fluidLayerType;
	private static AttachmentType<MoistureLayer> moistureLayerType;
	private static AttachmentType<io.github.nagsfov.ocwo.fluids.WakeLayer> wakeLayerType;

	@Override
	public String getPlatformName() {
		return "Fabric";
	}

	@Override
	public boolean isModLoaded(String modId) {
		return FabricLoader.getInstance().isModLoaded(modId);
	}

	@Override
	public boolean isDevelopmentEnvironment() {
		return FabricLoader.getInstance().isDevelopmentEnvironment();
	}

	// ---- Game rules ----

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(Ocwo.MOD_ID, path);
	}

	@Override
	public GameRule<Integer> registerIntRule(String id, int defaultValue, int min, int max) {
		return GameRuleBuilder.forInteger(defaultValue).range(min, max)
				.category(GameRuleCategory.MISC).buildAndRegister(id(id));
	}

	@Override
	public GameRule<Boolean> registerBooleanRule(String id, boolean defaultValue) {
		return GameRuleBuilder.forBoolean(defaultValue)
				.category(GameRuleCategory.MISC).buildAndRegister(id(id));
	}

	@Override
	public GameRule<Double> registerDoubleRule(String id, double defaultValue, double min, double max) {
		return GameRuleBuilder.forDouble(defaultValue).range(min, max)
				.category(GameRuleCategory.MISC).buildAndRegister(id(id));
	}

	// ---- Fluid layer (Fabric data attachment on chunks) ----

	@Override
	public void registerFluidLayer(boolean syncToClients) {
		fluidLayerType = AttachmentRegistry.create(FluidLayerConstants.ID, builder -> {
			builder.initializer(FluidLayer::new).persistent(FluidLayerConstants.CODEC);
			if (syncToClients) {
				builder.syncWith(FluidLayerConstants.STREAM_CODEC, AttachmentSyncPredicate.all());
			}
		});
	}

	@Override
	public FluidLayer getFluidLayer(ChunkAccess chunk) {
		return chunk.getAttached(fluidLayerType);
	}

	@Override
	public FluidLayer getOrCreateFluidLayer(ChunkAccess chunk) {
		return chunk.getAttachedOrCreate(fluidLayerType);
	}

	@Override
	public FluidLayer setFluidLayer(ChunkAccess chunk, FluidLayer layer) {
		return chunk.setAttached(fluidLayerType, layer);
	}

	// ---- Moisture layer (Fabric data attachment on chunks, saved, not synced) ----

	@Override
	public void registerMoistureLayer() {
		moistureLayerType = AttachmentRegistry.create(MoistureLayer.ID,
				builder -> builder.initializer(MoistureLayer::new).persistent(MoistureLayer.CODEC));
	}

	@Override
	public MoistureLayer getMoistureLayer(ChunkAccess chunk) {
		return moistureLayerType == null ? null : chunk.getAttached(moistureLayerType);
	}

	@Override
	public MoistureLayer getOrCreateMoistureLayer(ChunkAccess chunk) {
		return chunk.getAttachedOrCreate(moistureLayerType);
	}

	@Override
	public void registerWakeLayer() {
		wakeLayerType = AttachmentRegistry.create(io.github.nagsfov.ocwo.fluids.WakeLayer.ID,
				builder -> builder.initializer(io.github.nagsfov.ocwo.fluids.WakeLayer::new).persistent(io.github.nagsfov.ocwo.fluids.WakeLayer.CODEC));
	}

	@Override
	public io.github.nagsfov.ocwo.fluids.WakeLayer getWakeLayer(ChunkAccess chunk) {
		return wakeLayerType == null ? null : chunk.getAttached(wakeLayerType);
	}

	@Override
	public io.github.nagsfov.ocwo.fluids.WakeLayer getOrCreateWakeLayer(ChunkAccess chunk) {
		return chunk.getAttachedOrCreate(wakeLayerType);
	}

	// ---- Data and commands ----

	@Override
	public void registerServerReloadListener(Identifier id, PreparableReloadListener listener) {
		ResourceLoader.get(PackType.SERVER_DATA).registerReloadListener(id, listener);
	}

	@Override
	public void registerCommands(java.util.function.Consumer<CommandDispatcher<CommandSourceStack>> registrar) {
		CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> registrar.accept(dispatcher));
	}

	// ---- Debug panel ----

	@Override
	public void registerDebugPayload() {
		net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.clientboundPlay()
				.register(io.github.nagsfov.ocwo.debug.DebugPayload.TYPE, io.github.nagsfov.ocwo.debug.DebugPayload.STREAM_CODEC);
		net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.serverboundPlay()
				.register(io.github.nagsfov.ocwo.debug.DebugSubscribePayload.TYPE, io.github.nagsfov.ocwo.debug.DebugSubscribePayload.STREAM_CODEC);
		net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.registerGlobalReceiver(io.github.nagsfov.ocwo.debug.DebugSubscribePayload.TYPE,
				(payload, context) -> io.github.nagsfov.ocwo.debug.OcwoDebug.onSubscribe(context.player(), payload.mask()));
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register(
				(handler, server) -> io.github.nagsfov.ocwo.debug.OcwoDebug.onPlayerLeft(handler.getPlayer().getUUID()));
	}

	@Override
	public void sendDebug(net.minecraft.server.level.ServerPlayer player, io.github.nagsfov.ocwo.debug.DebugPayload payload) {
		if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, io.github.nagsfov.ocwo.debug.DebugPayload.TYPE)) {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player, payload);
		}
	}

	// ---- Blocks and fluids ----

	@Override
	public boolean isFlammable(BlockState state) {
		FlammableBlockRegistry.Entry entry = FlammableBlockRegistry.getDefaultInstance().get(state.getBlock());
		return entry != null && entry.getBurnOdds() > 0;
	}

	@Override
	public int getFluidTemperature(Fluid fluid) {
		return FluidVariantAttributes.getTemperature(FluidVariant.of(fluid));
	}

	@Override
	public Component getFluidName(Fluid fluid) {
		return FluidVariantAttributes.getName(FluidVariant.of(fluid));
	}

	@Override
	public SoundEvent getFluidFillSound(Fluid fluid) {
		return FluidVariantAttributes.getFillSound(FluidVariant.of(fluid));
	}

	@Override
	public SoundEvent getFluidEmptySound(Fluid fluid) {
		return FluidVariantAttributes.getEmptySound(FluidVariant.of(fluid));
	}

	// ---- Items ----

	@Override
	public void registerTooltipComponent(DataComponentType<? extends TooltipProvider> type) {
		ItemComponentTooltipProviderRegistry.addFirst(type);
	}
}
