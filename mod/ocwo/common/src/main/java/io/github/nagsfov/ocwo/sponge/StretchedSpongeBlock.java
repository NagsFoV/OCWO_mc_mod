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
 * side. {@link #FACING} points to the other half; {@link #ORIGIN} marks the half where the sponge was before it was
 * stretched. The two halves are one sponge that counts as two sharing their water (128 levels): it absorbs as soon
 * as one half touches water. It holds only while a sticky element touches each end (see SpongeLogic); broken, both
 * halves break.
 */
public class StretchedSpongeBlock extends Block {

	public static final EnumProperty<Direction> FACING = BlockStateProperties.FACING;
	public static final BooleanProperty WET = BooleanProperty.create("wet");
	public static final BooleanProperty ORIGIN = BooleanProperty.create("origin");

	public StretchedSpongeBlock(BlockBehaviour.Properties properties) {
		super(properties);
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP).setValue(WET, false).setValue(ORIGIN, false));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING, WET, ORIGIN);
	}

	@Override
	protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
		if (!oldState.is(state.getBlock()) && level instanceof ServerLevel sl) SpongeLogic.absorb(sl, pos);
	}

	@Override
	protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, @Nullable Orientation orientation, boolean movedByPiston) {
		if (level instanceof ServerLevel sl && !SpongeLogic.isQuiet()) {
			if (!SpongeLogic.checkStretched(sl, pos)) SpongeLogic.absorb(sl, pos);
		}
		super.neighborChanged(state, level, pos, block, orientation, movedByPiston);
	}

	@Override
	protected void affectNeighborsAfterRemoval(BlockState state, ServerLevel level, BlockPos pos, boolean movedByPiston) {
		if (!movedByPiston) SpongeLogic.onStretchedRemoved(level, pos, state);
		super.affectNeighborsAfterRemoval(state, level, pos, movedByPiston);
	}
}
