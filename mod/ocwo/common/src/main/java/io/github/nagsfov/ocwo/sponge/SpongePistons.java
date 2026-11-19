package io.github.nagsfov.ocwo.sponge;

import io.github.nagsfov.ocwo.moisture.Moisture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pistons and sponges (03-humidite-et-etats-des-blocs.md, "Éponges"), Valentin's rules of 19/11/2026. Before an
 * extending piston moves anything, its push line is read from the head; the first rule that applies is carried out,
 * and the line is read again:
 * <ol>
 *   <li>a sponge never pushes a block other than a sponge (slime and honey stuck to it move with it): it is crushed
 *   (destroyed, its water given back through its 4 side cells, dropped dry); in a blocked run of sponges, the two
 *   nearest to what pushes them are pressed into a double sponge (each gives back half its levels);</li>
 *   <li>a sponge pushed against a double or stretched sponge is crushed;</li>
 *   <li>a double sponge pressed again along its axis breaks: two sponges drop, each with its share;</li>
 *   <li>a stretched sponge pushed along its axis (by the piston head or another block) is one sponge again, in its far
 *   half (the surplus over 64 levels comes out).</li>
 * </ol>
 * Crushing or pressing a sponge that holds water needs a free side cell (air or water that is not full) and room for
 * all the water; otherwise the piston does not push at all. A sticky piston pulling a stretched sponge that is held on
 * the other side stretches it again: it breaks (one sponge drops, 64 levels at most, the rest comes out).
 */
public final class SpongePistons {

	private static final int U = Moisture.UNITS_PER_LEVEL;
	/** Cells of the push line read from the head (the push limit is 12 blocks). */
	private static final int LINE = 13;

	private SpongePistons() {
	}

	enum Kind {CRUSH, PRESS, COLLAPSE, BREAK_DOUBLE}

	record Action(Kind kind, BlockPos a, BlockPos b, Direction dir) {
	}

	/** The piston pushes nothing further than this cell: the line ends (air, plants, fluids, blocks that pop). */
	private static boolean ends(BlockState s) {
		return s.isAir() || s.canBeReplaced() || s.getBlock() instanceof LiquidBlock || s.getPistonPushReaction() == PushReaction.POPPED;
	}

	private static boolean glued(BlockState s) {
		return SpongeRules.GLUED_BLOCKS_MOVE_WITH_SPONGE && (s.is(Blocks.SLIME_BLOCK) || s.is(Blocks.HONEY_BLOCK));
	}

	/** The first sponge rule that applies on the push line of the piston at base (null: none). */
	static Action find(ServerLevel level, BlockPos base, Direction dir) {
		BlockState pusher = null; // null: the piston head
		for (int i = 1; i <= LINE; i++) {
			BlockPos p = base.relative(dir, i);
			if (!level.isLoaded(p)) return null;
			BlockState s = level.getBlockState(p);
			if (s.is(Blocks.MOVING_PISTON) || ends(s)) return null;
			if (pusher != null && OcwoSponges.isSponge(pusher)) {
				if (OcwoSponges.isSponge(s) || glued(s)) {
					pusher = s;
					continue;
				}
				// the run of sponges ending here is blocked (by any block, a double or stretched sponge included): the two
				// sponges nearest to what pushes them are pressed together (the double sponge against the piston); a lone
				// sponge is crushed
				int first = i - 1;
				while (first > 1 && OcwoSponges.isSponge(level.getBlockState(base.relative(dir, first - 1)))) first--;
				if (first < i - 1) return new Action(Kind.PRESS, base.relative(dir, first), base.relative(dir, first + 1), dir);
				return new Action(Kind.CRUSH, base.relative(dir, i - 1), null, dir);
			}
			if (OcwoSponges.isStretched(s)) {
				if (s.getValue(StretchedSpongeBlock.FACING) == dir && SpongeLogic.otherHalf(level, p, s) != null) {
					return new Action(Kind.COLLAPSE, p, p.relative(dir), dir);
				}
				return null; // across its axis: it does not move
			}
			if (OcwoSponges.isCompressed(s) && s.getValue(CompressedSpongeBlock.FACING).getAxis() == dir.getAxis()) {
				return new Action(Kind.BREAK_DOUBLE, p, null, dir);
			}
			pusher = s;
		}
		return null;
	}

	/** The 4 cells beside pos across the push axis, in a fixed order. */
	static List<BlockPos> sides(BlockPos pos, Direction dir) {
		List<BlockPos> out = new ArrayList<>(4);
		for (Direction d : SpongeLogic.ORDER) if (d.getAxis() != dir.getAxis()) out.add(pos.relative(d));
		return out;
	}

	/** Can the water of a crushed or pressed sponge come out here: a free side cell, and room for all of it? */
	private static boolean room(ServerLevel level, List<BlockPos> sides, int levels, Set<BlockPos> blocked) {
		if (levels <= 0) return true;
		boolean anyFree = false;
		for (BlockPos s : sides) anyFree |= SpongeLogic.free(level, s);
		if (!anyFree) return false;
		return !SpongeRules.CRUSH_NEEDS_ROOM_FOR_ALL || SpongeLogic.releaseFrom(level, sides, levels, blocked, true) == 0;
	}

	/** Carries out the action (or only checks it, dryRun). False: it cannot be done, the piston does not push. */
	static boolean apply(ServerLevel level, Action a, boolean dryRun) {
		switch (a.kind) {
			case CRUSH -> {
				BlockPos c = a.a;
				int u = SpongeLogic.units(level, c);
				int levels = u / U;
				if (levels == 0 && !SpongeRules.DRY_SPONGE_CRUSHED) return false;
				List<BlockPos> sides = sides(c, a.dir);
				Set<BlockPos> blocked = Set.of(c, c.relative(a.dir), c.relative(a.dir.getOpposite()));
				if (!room(level, sides, levels, blocked)) return false;
				if (dryRun) return true;
				BlockState was = level.getBlockState(c);
				SpongeLogic.whole(level, c, u); // whole levels only: the fraction goes up
				SpongeLogic.setUnits(level, c, 0);
				SpongeLogic.set(level, c, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				level.levelEvent(2001, c, Block.getId(was));
				SpongeLogic.releaseFrom(level, sides, levels, blocked, false);
				Block.popResource(level, c, OcwoSponges.spongeItem(0, 1)); // crushed: it drops dry
				return true;
			}
			case PRESS -> {
				BlockPos w = a.a, v = a.b; // w pushes v
				// whole levels: each gives back half its levels (rounded down), the double sponge keeps the rest
				int lw = SpongeLogic.units(level, w) / U, lv = SpongeLogic.units(level, v) / U;
				int levels = lw / 2 + lv / 2;
				int keep = (lw + lv - levels) * U;
				List<BlockPos> sides = new ArrayList<>(sides(w, a.dir));
				sides.addAll(sides(v, a.dir));
				Set<BlockPos> blocked = Set.of(w, v, v.relative(a.dir), w.relative(a.dir.getOpposite()));
				if (!room(level, sides, levels, blocked)) return false;
				if (dryRun) return true;
				SpongeLogic.whole(level, w, SpongeLogic.units(level, w)); // the fractions go up
				SpongeLogic.whole(level, v, SpongeLogic.units(level, v));
				SpongeLogic.setUnits(level, w, 0);
				SpongeLogic.set(level, w, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				SpongeLogic.setUnits(level, v, keep);
				SpongeLogic.set(level, v, OcwoSponges.COMPRESSED_SPONGE.defaultBlockState()
						.setValue(CompressedSpongeBlock.FACING, a.dir.getOpposite())
						.setValue(CompressedSpongeBlock.WET, keep >= U), Block.UPDATE_ALL);
				level.playSound(null, v, SoundEvents.WET_SPONGE_PLACE, SoundSource.BLOCKS, 1.0F, 0.8F);
				SpongeLogic.releaseFrom(level, sides, levels, blocked, false);
				SpongeLogic.updateLook(level, v);
				return true;
			}
			case COLLAPSE -> {
				if (dryRun) return true;
				// the block pushing it takes the near half's cell: no water there
				SpongeLogic.collapse(level, a.a, a.b, a.b, Set.of(a.a));
				return true;
			}
			case BREAK_DOUBLE -> {
				if (dryRun) return true;
				BlockState was = level.getBlockState(a.a);
				int u = SpongeLogic.units(level, a.a);
				SpongeLogic.set(level, a.a, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				SpongeLogic.setUnits(level, a.a, u);
				level.levelEvent(2001, a.a, Block.getId(was));
				SpongeLogic.breakCompressed(level, a.a);
				return true;
			}
		}
		return false;
	}

	/**
	 * Before an extending piston moves anything: carries out the sponge rules of its push line, one at a time, reading
	 * the line again after each. False: one cannot be carried out (no room for the water), the piston does not push.
	 */
	public static boolean beforeExtend(ServerLevel level, BlockPos base, Direction dir) {
		if (OcwoSponges.COMPRESSED_SPONGE == null) return true;
		for (int k = 0; k < 8; k++) {
			Action a = find(level, base, dir);
			if (a == null) return true;
			if (!apply(level, a, false)) return false;
		}
		return true;
	}

	/** Would a sponge rule let this piston extend (vanilla says it cannot push)? */
	public static boolean couldExtend(ServerLevel level, BlockPos base, Direction dir) {
		if (OcwoSponges.COMPRESSED_SPONGE == null) return false;
		Action a = find(level, base, dir);
		return a != null && apply(level, a, true);
	}

	/**
	 * A sticky piston starts retracting: a stretched sponge in front of its head, held by a sticky element at its far
	 * end, would be stretched again: it breaks (one sponge drops with 64 levels at most, the rest comes out).
	 */
	public static void beforeRetract(ServerLevel level, BlockPos base, Direction dir) {
		if (OcwoSponges.STRETCHED_SPONGE == null) return;
		BlockPos near = base.relative(dir, 2);
		BlockState s = level.getBlockState(near);
		if (!OcwoSponges.isStretched(s) || s.getValue(StretchedSpongeBlock.FACING) != dir) return;
		BlockPos far = SpongeLogic.otherHalf(level, near, s);
		if (far == null || !SpongeLogic.stickyAt(level, far.relative(dir), dir.getOpposite())) return;
		int pool = SpongeLogic.units(level, near) + SpongeLogic.units(level, far);
		BlockState fs = level.getBlockState(far);
		SpongeLogic.set(level, near, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		SpongeLogic.set(level, far, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		level.levelEvent(2001, near, Block.getId(s));
		level.levelEvent(2001, far, Block.getId(fs));
		SpongeLogic.breakInto(level, near, far, pool);
	}
}
