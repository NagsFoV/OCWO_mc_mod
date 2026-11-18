package io.github.nagsfov.ocwo.mixins._fluids.tweaks;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.nagsfov.ocwo.fluids.FluidUtils;
import io.github.nagsfov.ocwo.sponge.SpongeLogic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
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

	/** OCWO sponges: a piston that cannot push presses a sponge instead (see SpongeLogic). */
	@Inject(method = "moveBlocks", at = @At("HEAD"), cancellable = true)
	void ocwoPressSponge(Level level, BlockPos pistonPos, Direction direction, boolean extending, CallbackInfoReturnable<Boolean> cir) {
		if (level.isClientSide()) return;
		if (extending) {
			if (SpongeLogic.tryPress((ServerLevel) level, pistonPos, direction, isSticky)) cir.setReturnValue(true);
		} else {
			SpongeLogic.saveHeadWater((ServerLevel) level, pistonPos, direction);
		}
	}

	/** OCWO sponges: the piston gets its extension event when it will press a sponge. */
	@WrapOperation(method = "checkIfExtend", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/piston/PistonStructureResolver;resolve()Z"))
	boolean ocwoCanPress(PistonStructureResolver resolver, Operation<Boolean> original,
	                      @Local(argsOnly = true) Level level, @Local(argsOnly = true) BlockPos pos, @Local Direction direction) {
		if (original.call(resolver)) return true;
		return !level.isClientSide() && SpongeLogic.canPress((ServerLevel) level, pos, direction);
	}

	/** OCWO sponges: a sticky piston pulling a sponge held on the other side stretches it. */
	@WrapOperation(method = "triggerEvent", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/piston/PistonBaseBlock;moveBlocks(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Z)Z",
			ordinal = 1))
	boolean ocwoStretchSponge(PistonBaseBlock self, Level level, BlockPos pos, Direction direction, boolean extending, Operation<Boolean> original) {
		if (!level.isClientSide() && SpongeLogic.tryStretch((ServerLevel) level, pos, direction)) return true;
		return original.call(self, level, pos, direction, extending);
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
