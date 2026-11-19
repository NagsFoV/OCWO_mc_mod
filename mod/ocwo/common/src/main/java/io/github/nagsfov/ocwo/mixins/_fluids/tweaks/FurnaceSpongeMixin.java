package io.github.nagsfov.ocwo.mixins._fluids.tweaks;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.nagsfov.ocwo.fluids.FluidUtils;
import io.github.nagsfov.ocwo.fluids.WaterReserves;
import io.github.nagsfov.ocwo.moisture.Moisture;
import io.github.nagsfov.ocwo.sponge.OcwoSponges;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * OCWO sponges: drying a wet sponge in a furnace sends its water up as steam (to the clouds of the zone); a bucket in
 * the fuel slot fills only if the sponge held at least a bucket of water (8 levels), which then does not go up.
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public abstract class FurnaceSpongeMixin {

	@Shadow
	protected NonNullList<ItemStack> items;

	@Inject(method = "serverTick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity;burn(Lnet/minecraft/core/NonNullList;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemStack;)V"))
	private static void ocwoSpongeSteam(ServerLevel level, BlockPos pos, BlockState state, AbstractFurnaceBlockEntity entity, CallbackInfo ci) {
		NonNullList<ItemStack> items = ((FurnaceSpongeMixin) (Object) entity).items;
		ItemStack input = items.get(0);
		if (!input.is(Items.WET_SPONGE)) return;
		int levels = OcwoSponges.itemLevels(input);
		boolean bucket = items.get(1).is(Items.BUCKET) && levels >= FluidUtils.MAX_LEVEL;
		int steam = bucket ? levels - FluidUtils.MAX_LEVEL : levels;
		if (steam > 0) WaterReserves.toClouds(level, pos, (long) steam * Moisture.UNITS_PER_LEVEL);
	}

	/** Vanilla fills a bucket from any wet sponge: only from one holding at least a bucket of water. */
	@WrapOperation(method = "burn", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/item/ItemStack;is(Ljava/lang/Object;)Z", ordinal = 0))
	private static boolean ocwoSpongeBucket(ItemStack stack, Object item, Operation<Boolean> original) {
		boolean is = original.call(stack, item);
		if (!is || item != Items.WET_SPONGE || OcwoSponges.SPONGE_WATER == null) return is;
		return OcwoSponges.itemLevels(stack) >= FluidUtils.MAX_LEVEL;
	}
}
