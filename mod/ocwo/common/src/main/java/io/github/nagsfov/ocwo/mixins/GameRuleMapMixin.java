package io.github.nagsfov.ocwo.mixins;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.serialization.Codec;
import io.github.nagsfov.ocwo.util.LenientGameRules;
import net.minecraft.world.level.gamerules.GameRuleMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Function;

/**
 * Saved game rules: a rule of a mod that is not installed (Axiom in a copy of a world, for example) made the whole
 * list unreadable, and the world fell back to the default rules. Unknown or invalid rules are now skipped one by one.
 */
@Mixin(GameRuleMap.class)
public abstract class GameRuleMapMixin {

	@SuppressWarnings({"rawtypes", "unchecked"})
	@WrapOperation(method = "<clinit>", at = @At(value = "INVOKE",
			target = "Lcom/mojang/serialization/Codec;dispatchedMap(Lcom/mojang/serialization/Codec;Ljava/util/function/Function;)Lcom/mojang/serialization/Codec;"))
	private static Codec ocwo$lenient(Codec keyCodec, Function valueCodec, Operation<Codec> original) {
		return LenientGameRules.wrap(original.call(keyCodec, valueCodec));
	}
}
