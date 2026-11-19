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
 * Double sponge: two sponges pressed together by a piston along one axis (each gave back half its water). A wrung
 * sponge: it absorbs nothing while compressed. {@link #FACING} points to the side where the piston head was. It holds
 * only while a solid block touches each side along its axis; otherwise it splits back into two sponges (see
 * SpongeLogic). Pushed across its axis, it moves; pressed again along it, it breaks. Broken, it drops two sponges,
 * each with its half of the water.
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
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		if (!oldState.is(state.getBlock()) && level instanceof ServerLevel sl && !SpongeLogic.isQuiet()) SpongeLogic.checkCompressed(sl, pos);
	}

	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, @Nullable Orientation orientation, boolean movedByPiston) {
		if (level instanceof ServerLevel sl && !SpongeLogic.isQuiet()) SpongeLogic.checkCompressed(sl, pos);
		super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		if (!movedByPiston) SpongeLogic.onCompressedRemoved(level, pos);
		super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
	}
}
