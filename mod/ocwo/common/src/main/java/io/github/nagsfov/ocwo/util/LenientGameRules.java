package io.github.nagsfov.ocwo.util;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import io.github.nagsfov.ocwo.Ocwo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.gamerules.GameRule;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reads saved game rules one by one, skipping the unknown or invalid ones (see GameRuleMapMixin). */
public final class LenientGameRules {

	private LenientGameRules() {
	}

	public static Codec<Map<GameRule<?>, Object>> wrap(Codec<Map<GameRule<?>, Object>> strict) {
		return new Codec<>() {
			@Override
			public <T> DataResult<Pair<Map<GameRule<?>, Object>, T>> decode(DynamicOps<T> ops, T input) {
				DataResult<Pair<Map<GameRule<?>, Object>, T>> direct = strict.decode(ops, input);
				if (direct.isSuccess()) return direct;
				Map<GameRule<?>, Object> out = new LinkedHashMap<>();
				return ops.getMapValues(input).map(entries -> {
					entries.forEach(e -> {
						String key = ops.getStringValue(e.getFirst()).result().orElse(null);
						Identifier id = key == null ? null : Identifier.tryParse(key);
						GameRule<?> rule = id == null ? null : BuiltInRegistries.GAME_RULE.getValue(id);
						if (rule == null) {
							Ocwo.LOGGER.warn("Saved game rule {} is unknown (mod not installed?): skipped, the other rules are kept", key);
							return;
						}
						rule.valueCodec().parse(new Dynamic<>(ops, e.getSecond())).result().ifPresentOrElse(
								v -> out.put(rule, v),
								() -> Ocwo.LOGGER.warn("Saved game rule {} has an invalid value: skipped", key));
					});
					return Pair.of(out, input);
				});
			}

			@Override
			public <T> DataResult<T> encode(Map<GameRule<?>, Object> input, DynamicOps<T> ops, T prefix) {
				return strict.encode(input, ops, prefix);
			}
		};
	}
}
