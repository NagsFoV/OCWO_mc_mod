package io.github.nagsfov.ocwo.sponge;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import org.jetbrains.annotations.Nullable;

/**
 * Double sponge: two sponges pressed together by a piston (each gave back half its water). A wrung sponge: it
 * absorbs nothing while compressed. {@link #FACING} points to the piston head; when the head is gone, it splits back
 * into two sponges (the second one where the head was), which absorb again. Mined, it drops two sponges and gives
 * back its water.
 */
public class CompressedSpongeBlock extends Block {

	public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
	public static final BooleanProperty WET = StretchedSpongeBlock.WET;

	public CompressedSpongeBlock(BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP).setValue(WET, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, WET);
	}

	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, @Nullable Orientation orientation, boolean movedByPiston) {
		if (level instanceof ServerLevel sl) SpongeLogic.trySplit(sl, pos, state);
		super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
	}
}
