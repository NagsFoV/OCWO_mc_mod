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
 * One half of a stretched sponge: a sponge pulled by a sticky piston while held by a sticky element on the other
 * side. {@link #FACING} points to the other half. Each half holds as much as a sponge, so the stretched sponge holds
 * twice as much, and absorbs water. When it becomes one block again (pushed back by the piston) or one half is
 * destroyed, it is a plain sponge again and gives back what it holds over a sponge's capacity.
 */
public class StretchedSpongeBlock extends Block {

	public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
	public static final BooleanProperty WET = BooleanProperty.create("wet");

	public StretchedSpongeBlock(BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP).setValue(WET, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, WET);
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		if (!oldState.is(state.getBlock()) && level instanceof ServerLevel sl) SpongeLogic.absorb(sl, pos);
	}

	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, @Nullable Orientation orientation, boolean movedByPiston) {
		if (level instanceof ServerLevel sl) SpongeLogic.absorb(sl, pos);
		super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		SpongeLogic.onStretchedRemoved(level, pos, state);
		super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
	}
}
