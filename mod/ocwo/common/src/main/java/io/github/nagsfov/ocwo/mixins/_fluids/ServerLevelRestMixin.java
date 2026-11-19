package io.github.nagsfov.ocwo.mixins._fluids;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.nagsfov.ocwo.fluids.WaterRest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;

/**
 * The block ticks of a new chunk during its first ticks (sand and gravel of the world generation left above a cave,
 * that fall as soon as the chunk ticks), and the block ticks scheduled by the end of the generation, are the end of
 * the generation: they do not wake the water (see WaterRest.isNewborn and takeGenerationTick). A new chunk is born
 * at rest, as without them.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelRestMixin {

	@WrapMethod(method = "tickBlock")
	private void ocwo$newbornBlockTick(BlockPos pos, Block type, Operation<Void> original) {
		// (both asked: a generation tick is used up even in a new chunk)
		boolean generation = WaterRest.takeGenerationTick(pos) | WaterRest.isNewborn((ServerLevel) (Object) this, pos);
		if (!generation) {
			original.call(pos, type);
			return;
		}
		WaterRest.suppress(true);
		try {
			original.call(pos, type);
		} finally {
			WaterRest.suppress(false);
		}
	}
}
