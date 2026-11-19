package io.github.nagsfov.ocwo.mixins._fluids.tweaks;

import io.github.nagsfov.ocwo.sponge.OcwoSponges;
import io.github.nagsfov.ocwo.sponge.SpongeLogic;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** OCWO sponges: a sponge item carries its water, and jumping on a sponge squeezes it (see SpongeLogic). */
@Mixin(Block.class)
public class BlockSpongeMixin {

	/** The item a removed sponge drops takes its water. */
	@ModifyVariable(method = "popResource(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)V",
			at = @At("HEAD"), argsOnly = true)
	private static ItemStack ocwoSpongeDrop(ItemStack stack, Level level, BlockPos pos, ItemStack original) {
		if (level instanceof ServerLevel sl && OcwoSponges.isSpongeItem(stack)) return SpongeLogic.onDrop(sl, pos, stack);
		return stack;
	}

	/** A sponge placed from an item gets its water back. */
	@Inject(method = "setPlacedBy", at = @At("HEAD"))
	void ocwoSpongePlaced(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack itemStack, CallbackInfo ci) {
		if (level instanceof ServerLevel sl && OcwoSponges.isSponge(state)) SpongeLogic.onPlaced(sl, pos, state, itemStack);
	}

	/** Jumping on a full sponge squeezes water out of it. */
	@Inject(method = "fallOn", at = @At("HEAD"))
	void ocwoSpongeFall(Level level, BlockState state, BlockPos pos, Entity entity, double fallDistance, CallbackInfo ci) {
		if (level instanceof ServerLevel sl && (OcwoSponges.isSponge(state) || OcwoSponges.isStretched(state))) {
			SpongeLogic.onFallOn(sl, state, pos, fallDistance);
		}
	}
}
