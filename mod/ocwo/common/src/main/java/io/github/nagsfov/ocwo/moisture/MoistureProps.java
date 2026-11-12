package io.github.nagsfov.ocwo.moisture;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

/**
 * Moisture properties of one block, resolved from its material and its own settings (see 03).
 *
 * @param material       material id (for display)
 * @param capacity       moisture the block can hold, in units ({@link Moisture#UNITS_PER_LEVEL} per water level)
 * @param permeability   default permeability, 0 (watertight) to 10
 * @param along          permeability along the block's axis or facing (oriented blocks), or -1
 * @param across         permeability across it, or -1
 * @param up             permeability towards up (non-oriented blocks), or -1
 * @param down           permeability towards down, or -1
 * @param side           permeability towards the sides, or -1
 * @param stages         wet stages are visible
 * @param retains        holds its water: never seeps
 * @param fixedWater     water the block is made of (mud, ice, snow…), in units, carried by the item when mined
 * @param transformTo    block it turns into when wet enough, or null
 * @param transformStage stage at which it turns into {@code transformTo}
 * @param revertTo       for a transform target (mud): block it turns back into while drying, or null
 * @param revertStage    it turns back under this stage
 */
public record MoistureProps(String material, int capacity, int permeability,
                            int along, int across, int up, int down, int side,
                            boolean stages, boolean retains, int fixedWater,
                            @Nullable Block transformTo, Moisture.Stage transformStage,
                            @Nullable Block revertTo, Moisture.Stage revertStage) {

	/** Permeability of this block (in this state) through the face {@code dir}. */
	public int permeability(BlockState state, Direction dir) {
		if (along >= 0 || across >= 0) {
			Direction.Axis axis = null;
			if (state.hasProperty(BlockStateProperties.AXIS)) axis = state.getValue(BlockStateProperties.AXIS);
			else if (state.hasProperty(BlockStateProperties.FACING)) axis = state.getValue(BlockStateProperties.FACING).getAxis();
			else if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) axis = state.getValue(BlockStateProperties.HORIZONTAL_FACING).getAxis();
			if (axis != null) {
				int v = dir.getAxis() == axis ? along : across;
				return v >= 0 ? v : permeability;
			}
		}
		int v = switch (dir) {
			case UP -> up;
			case DOWN -> down;
			default -> side;
		};
		return v >= 0 ? v : permeability;
	}

	public boolean holdsMoisture() {
		return capacity > 0;
	}

	public MoistureProps withRevert(Block to, Moisture.Stage stage) {
		return new MoistureProps(material, capacity, permeability, along, across, up, down, side, stages, retains,
				fixedWater, transformTo, transformStage, to, stage);
	}
}
