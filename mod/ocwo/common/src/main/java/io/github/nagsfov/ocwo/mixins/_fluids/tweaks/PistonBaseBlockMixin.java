package io.github.nagsfov.ocwo.mixins._fluids.tweaks;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.nagsfov.ocwo.fluids.FluidUtils;
import io.github.nagsfov.ocwo.sponge.CompressedSpongeBlock;
import io.github.nagsfov.ocwo.sponge.OcwoSponges;
import io.github.nagsfov.ocwo.sponge.SpongeLogic;
import io.github.nagsfov.ocwo.sponge.SpongePistons;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PistonBaseBlock.class)
public class PistonBaseBlockMixin {

	@Shadow
	@Final
	private boolean isSticky;

	/**
	 * OCWO sponges: before an extending piston moves anything, the sponge rules of its push line (SpongePistons); it
	 * does not push when one cannot be carried out.
	 */
	@Inject(method = "moveBlocks", at = @At("HEAD"), cancellable = true)
	void ocwoSpongeRules(Level level, BlockPos pistonPos, Direction direction, boolean extending, CallbackInfoReturnable<Boolean> cir) {
		if (level.isClientSide()) return;
		if (extending) {
			SpongeLogic.beginSync();
			try {
				if (!SpongePistons.beforeExtend((ServerLevel) level, pistonPos, direction)) cir.setReturnValue(false);
			} finally {
				// the clients get these changes before the piston event: they move the same blocks as the server
				SpongeLogic.endSync((ServerLevel) level);
			}
		} else {
			SpongeLogic.saveHeadWater((ServerLevel) level, pistonPos, direction);
		}
	}

	/**
	 * OCWO sponges: a retracting sticky piston with a sponge in front of its head, held at its other end by an anchored
	 * sticky element (not pulled along), stretches it instead of pulling (the head's cell is already empty here).
	 */
	@WrapOperation(method = "moveBlocks", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/piston/PistonStructureResolver;resolve()Z"))
	boolean ocwoStretchIfAnchored(PistonStructureResolver resolver, Operation<Boolean> original,
	                              @Local(argsOnly = true) Level level, @Local(argsOnly = true) BlockPos pos,
	                              @Local(argsOnly = true) Direction direction, @Local(argsOnly = true) boolean extending) {
		boolean ok = original.call(resolver);
		if (extending || !isSticky || level.isClientSide()) return ok;
		SpongeLogic.beginSync();
		try {
			if (SpongeLogic.stretchIfAnchored((ServerLevel) level, pos, direction, ok ? resolver.getToPush() : null)) return false;
		} finally {
			SpongeLogic.endSync((ServerLevel) level);
		}
		return ok;
	}

	/** OCWO sponges: the piston gets its extension event when a sponge rule lets it extend. */
	@WrapOperation(method = "checkIfExtend", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/piston/PistonStructureResolver;resolve()Z"))
	boolean ocwoCanPress(PistonStructureResolver resolver, Operation<Boolean> original,
	                      @Local(argsOnly = true) Level level, @Local(argsOnly = true) BlockPos pos, @Local Direction direction) {
		if (original.call(resolver)) return true;
		return !level.isClientSide() && SpongePistons.couldExtend((ServerLevel) level, pos, direction);
	}

	/** OCWO sponges: a retracting sticky piston would stretch a stretched sponge again: it breaks. */
	@Inject(method = "triggerEvent", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/Level;getRandom()Lnet/minecraft/util/RandomSource;", ordinal = 0))
	void ocwoRestretch(BlockState state, Level level, BlockPos pos, int b0, int b1, CallbackInfoReturnable<Boolean> cir) {
		if (level.isClientSide() || !isSticky || (b0 != 1 && b0 != 2)) return;
		SpongeLogic.beginSync();
		try {
			SpongePistons.beforeRetract((ServerLevel) level, pos, state.getValue(PistonBaseBlock.FACING));
		} finally {
			SpongeLogic.endSync((ServerLevel) level);
		}
	}

	/** OCWO sponges: a double sponge moves only across its axis (along it, a piston presses it again: SpongePistons). */
	@Inject(method = "isPushable", at = @At("HEAD"), cancellable = true)
	private static void ocwoDoubleSpongeAxis(BlockState state, Level level, BlockPos pos, Direction direction, boolean allowDestroyable,
	                                         Direction connectionDirection, CallbackInfoReturnable<Boolean> cir) {
		if (OcwoSponges.isCompressed(state) && state.getValue(CompressedSpongeBlock.FACING).getAxis() == direction.getAxis()) cir.setReturnValue(false);
	}

	@Inject(method = "moveBlocks", at = @At(value = "INVOKE",
			target = "Lcom/google/common/collect/Maps;newHashMap()Ljava/util/HashMap;",
			ordinal = 0
	), slice = @Slice(from = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/piston/PistonStructureResolver;resolve()Z"
	)), cancellable = true)
	void moveBlocks(Level level,
	                BlockPos blockPos,
	                Direction direction,
	                boolean bl,
	                CallbackInfoReturnable<Boolean> cir,
	                @Local(type = PistonStructureResolver.class) PistonStructureResolver pistonStructureResolver
	) {
		if (!level.isClientSide() && !FluidUtils.pistonHook((ServerLevel) level, pistonStructureResolver, blockPos, direction)) {
			cir.setReturnValue(false);
			return;
		}
		// OCWO: moved blocks carry their moisture (a wet sponge keeps its water)
		if (!level.isClientSide()) {
			SpongeLogic.carryMoisture((ServerLevel) level, pistonStructureResolver.getToPush(), pistonStructureResolver.getPushDirection());
		}
	}
}
