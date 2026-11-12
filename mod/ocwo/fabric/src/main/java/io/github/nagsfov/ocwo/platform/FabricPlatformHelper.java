package io.github.nagsfov.ocwo.platform;

import io.github.nagsfov.ocwo.Ocwo;
import io.github.nagsfov.ocwo.fluids.layer.FluidLayer;
import io.github.nagsfov.ocwo.fluids.layer.FluidLayerConstants;
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
