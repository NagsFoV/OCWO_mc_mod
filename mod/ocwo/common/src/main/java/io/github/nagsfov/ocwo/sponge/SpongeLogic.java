package io.github.nagsfov.ocwo.sponge;

import io.github.nagsfov.ocwo.fluids.CustomFluidTicks;
import io.github.nagsfov.ocwo.fluids.FluidUtils;
import io.github.nagsfov.ocwo.moisture.Moisture;
import io.github.nagsfov.ocwo.moisture.MoistureData;
import io.github.nagsfov.ocwo.moisture.MoistureProps;
import io.github.nagsfov.ocwo.moisture.MoistureSimulation;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * OCWO sponges (03-humidite-et-etats-des-blocs.md, "Éponges"). A sponge stores the water it absorbs as moisture of
 * its cell (nothing is destroyed as in vanilla); water is conserved exactly everywhere (1 level = 16 units).
 * <ul>
 *   <li>absorption: at once when placed or when a neighbour changes, through connected water up to 6 blocks away,
 *   up to its capacity (64 levels);</li>
 *   <li>crushing: a wet sponge pushed by a piston against a block that does not move gives back all its water in
 *   the free cells around it (never into that block), then through the piston head (it holds water); the sponge
 *   breaks and drops as a (dry) sponge item;</li>
 *   <li>double sponge: two sponges pressed together each give back half their water and become one compressed
 *   block, which splits back into two sponges when the piston head goes away;</li>
 *   <li>stretched sponge: a sponge held by a sticky element on the other side, pulled by a sticky piston, stretches
 *   into two blocks that hold twice as much; pushed back, or with one half destroyed, it is one sponge again and
 *   gives back the surplus.</li>
 * </ul>
 * Whatever finds no room stays as moisture of the cell, released later by the moisture simulation (never lost).
 */
public final class SpongeLogic {

	private static final int U = Moisture.UNITS_PER_LEVEL;
	public static final int SPONGE_LEVELS = 64;
	private static final int ABSORB_DEPTH = 6;
	private static final int ABSORB_CELLS = 64;
	private static final int RELEASE_STEPS = 256;
	/** Fixed order: water goes down first, then sideways (deterministic, like the rest of the flow). */
	private static final Direction[] ORDER = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP};

	/** true while this class changes blocks itself (no removal reaction, no moisture accounting). */
	private static boolean quiet;
	private static boolean absorbing;

	private SpongeLogic() {
	}

	private static MoistureSimulation sim(ServerLevel level) {
		return CustomFluidTicks.get(level).moisture();
	}

	public static int capacity(BlockState state) {
		MoistureProps p = MoistureData.get(state);
		return p != null && p.holdsMoisture() ? p.capacity() : SPONGE_LEVELS * U;
	}

	private static boolean isWater(FluidState fs) {
		return !fs.isEmpty() && fs.getType().isSame(Fluids.WATER);
	}

	/** Sets a block without moisture accounting and without the sponges' own removal reactions. */
	static void set(ServerLevel level, BlockPos pos, BlockState state, int flags) {
		boolean was = quiet;
		quiet = true;
		try {
			sim(level).setBlockQuietly(pos, state, flags);
		} finally {
			quiet = was;
		}
	}

	// ------------------------------------------------------------------ absorption and look

	/** Absorbs at once the water around a sponge (plain or stretched), up to its capacity. */
	public static void absorb(ServerLevel level, BlockPos pos) {
		if (absorbing) return;
		BlockState state = level.getBlockState(pos);
		if (!OcwoSponges.isSponge(state) && !OcwoSponges.isStretched(state)) return;
		MoistureSimulation sim = sim(level);
		int u = sim.units(pos);
		int room = (capacity(state) - u) / U;
		int taken = 0;
		if (room > 0) {
			absorbing = true;
			try {
				ArrayDeque<BlockPos> queue = new ArrayDeque<>();
				ArrayDeque<Integer> depth = new ArrayDeque<>();
				LongOpenHashSet seen = new LongOpenHashSet();
				seen.add(pos.asLong());
				queue.add(pos);
				depth.add(0);
				int cells = 0;
				search:
				while (!queue.isEmpty()) {
					BlockPos cur = queue.poll();
					int d = depth.poll();
					if (d >= ABSORB_DEPTH) continue;
					for (Direction dir : ORDER) {
						BlockPos n = cur.relative(dir);
						if (!seen.add(n.asLong()) || !level.isLoaded(n)) continue;
						FluidState fs = level.getFluidState(n);
						if (!isWater(fs) || !MoistureSimulation.canTakeWaterFrom(level.getBlockState(n))) continue;
						if (++cells > ABSORB_CELLS) break search;
						int take = Math.min(fs.getAmount(), room - taken);
						if (take > 0 && fs.getType() instanceof FlowingFluid ff) {
							FluidUtils.setFluid(level, n, ff, fs.getAmount() - take, false);
							taken += take;
						}
						if (taken >= room) break search;
						queue.add(n);
						depth.add(d + 1);
					}
				}
			} finally {
				absorbing = false;
			}
		}
		if (taken > 0) {
			sim.setUnits(pos, u + taken * U);
			level.playSound(null, pos, SoundEvents.SPONGE_ABSORB, SoundSource.BLOCKS, 1.0F, 1.0F);
		}
		updateLook(level, pos);
	}

	/** Wet look as soon as the block holds one water level. */
	public static void updateLook(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		boolean wet = sim(level).units(pos) >= U;
		BlockState want = state;
		if (OcwoSponges.isSponge(state)) {
			want = (wet ? Blocks.WET_SPONGE : Blocks.SPONGE).defaultBlockState();
		} else if (OcwoSponges.isStretched(state)) {
			want = state.setValue(StretchedSpongeBlock.WET, wet);
		} else if (OcwoSponges.isCompressed(state)) {
			want = state.setValue(CompressedSpongeBlock.WET, wet);
		}
		if (want != state) set(level, pos, want, Block.UPDATE_ALL);
	}

	/** Wet sponge placed where water evaporates (Nether): its water evaporates, as in vanilla. */
	public static void evaporate(ServerLevel level, BlockPos pos) {
		sim(level).setUnits(pos, 0);
		set(level, pos, Blocks.SPONGE.defaultBlockState(), Block.UPDATE_ALL);
		level.levelEvent(2009, pos, 0);
		level.playSound(null, pos, SoundEvents.WET_SPONGE_DRIES, SoundSource.BLOCKS, 1.0F, (1.0F + level.getRandom().nextFloat() * 0.2F) * 0.7F);
	}

	// ------------------------------------------------------------------ giving water back

	/**
	 * Places {@code levels} water levels in the free cells reachable from the origins (nearest first, at most 8 per
	 * cell), never in the blocked cells. Returns what found no room.
	 */
	static int release(ServerLevel level, List<BlockPos> origins, int levels, Set<BlockPos> blocked) {
		if (levels <= 0) return 0;
		LongOpenHashSet visited = new LongOpenHashSet();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		for (BlockPos o : origins) {
			visited.add(o.asLong());
			queue.add(o);
		}
		for (BlockPos b : blocked) visited.add(b.asLong());
		int steps = 0;
		while (!queue.isEmpty() && levels > 0 && steps++ < RELEASE_STEPS) {
			BlockPos cur = queue.poll();
			boolean origin = origins.contains(cur);
			for (Direction dir : ORDER) {
				if (levels <= 0) break;
				BlockPos n = cur.relative(dir);
				if (visited.contains(n.asLong()) || !level.isLoaded(n)) continue;
				BlockState ns = level.getBlockState(n);
				boolean pathBlocked = origin
						? FluidUtils.isPathBlocked(null, Shapes.empty(), ns, ns.getCollisionShape(level, n), dir)
						: FluidUtils.isPathBlocked(level, cur, n);
				if (pathBlocked) continue;
				visited.add(n.asLong());
				FluidState fs = level.getFluidState(n);
				boolean water = isWater(fs);
				if (!fs.isEmpty() && !water) continue;
				if (!water && !ns.isAir() && !ns.canBeReplaced(Fluids.WATER) && !FluidUtils.isFluidStateOverrided(ns)) continue;
				int have = water ? fs.getAmount() : 0;
				int put = Math.min(levels, FluidUtils.MAX_LEVEL - have);
				if (put > 0 && !(water && FluidUtils.isFalling(fs))) {
					FluidUtils.setFluid(level, n, Fluids.WATER, have + put, false);
					levels -= put;
				}
				queue.add(n);
			}
		}
		return levels;
	}

	/** Puts water into the cell itself (the piston head holds water). Returns what did not fit. */
	private static int intoCell(ServerLevel level, BlockPos pos, int levels) {
		if (levels <= 0) return 0;
		FluidState fs = level.getFluidState(pos);
		if (!fs.isEmpty() && !isWater(fs)) return levels;
		if (!FluidUtils.isFluidStateOverrided(level.getBlockState(pos))) return levels;
		int have = isWater(fs) ? fs.getAmount() : 0;
		int put = Math.min(levels, FluidUtils.MAX_LEVEL - have);
		if (put <= 0) return levels;
		FluidUtils.setFluid(level, pos, Fluids.WATER, have + put, false);
		return levels - put;
	}

	/** A sponge holding more than its capacity gives the surplus back around it (what finds no room seeps later). */
	private static void releaseSurplus(ServerLevel level, BlockPos pos, Set<BlockPos> blocked) {
		MoistureSimulation sim = sim(level);
		int u = sim.units(pos);
		int over = u - capacity(level.getBlockState(pos));
		if (over <= 0) return;
		int levels = Math.min(u / U, (over + U - 1) / U);
		int left = release(level, List.of(pos), levels, blocked);
		sim.setUnits(pos, u - (levels - left) * U);
	}

	/** A sponge block went away (mined, burnt, exploded…): its water comes back around its cell at once. */
	public static void onSpongeGone(ServerLevel level, BlockPos pos, int units) {
		if (quiet || units < U) return;
		MoistureSimulation sim = sim(level);
		int levels = units / U;
		int left = release(level, List.of(pos), levels, Set.of());
		left = intoCell(level, pos, left);
		sim.setUnits(pos, units - (levels - left) * U);
	}

	// ------------------------------------------------------------------ pistons

	private static boolean isStickyAnchor(BlockState s, Direction pull) {
		if (s.is(Blocks.SLIME_BLOCK) || s.is(Blocks.HONEY_BLOCK)) return true;
		Direction back = pull.getOpposite();
		if (s.is(Blocks.PISTON_HEAD)) {
			return s.getValue(PistonHeadBlock.TYPE) == PistonType.STICKY && s.getValue(PistonHeadBlock.FACING) == back;
		}
		return s.is(Blocks.STICKY_PISTON) && s.getValue(PistonBaseBlock.FACING) == back && !s.getValue(PistonBaseBlock.EXTENDED);
	}

	private static boolean isStretchedPair(ServerLevel level, BlockPos first, Direction dir) {
		BlockState a = level.getBlockState(first);
		if (!OcwoSponges.isStretched(a) || a.getValue(StretchedSpongeBlock.FACING) != dir) return false;
		BlockState b = level.getBlockState(first.relative(dir));
		return OcwoSponges.isStretched(b) && b.getValue(StretchedSpongeBlock.FACING) == dir.getOpposite();
	}

	/** The piston cannot push what is in front of it: does it press a sponge instead? */
	public static boolean canPress(ServerLevel level, BlockPos base, Direction dir) {
		BlockPos arm = base.relative(dir);
		BlockState front = level.getBlockState(arm);
		if (OcwoSponges.isSponge(front)) {
			if (OcwoSponges.COMPRESSED_SPONGE != null && OcwoSponges.isSponge(level.getBlockState(arm.relative(dir)))) return true;
			return sim(level).units(arm) > 0;
		}
		return isStretchedPair(level, arm, dir);
	}

	/** Extending piston that cannot push: presses a sponge if there is one. Returns true if it did (the piston extends). */
	public static boolean tryPress(ServerLevel level, BlockPos base, Direction dir, boolean sticky) {
		if (!canPress(level, base, dir)) return false; // cheap: a sponge in front of the piston
		if (new PistonStructureResolver(level, base, dir, true).resolve()) return false; // it can push: no press
		BlockPos arm = base.relative(dir);
		BlockState front = level.getBlockState(arm);
		BlockState next = level.getBlockState(arm.relative(dir));
		if (OcwoSponges.isStretched(front)) unstretch(level, base, dir, sticky);
		else if (OcwoSponges.isSponge(next) && OcwoSponges.COMPRESSED_SPONGE != null) pressTwo(level, base, dir, sticky);
		else crush(level, base, dir, sticky);
		level.updateNeighborsAt(arm, Blocks.PISTON_HEAD);
		return true;
	}

	private static void extendBase(ServerLevel level, BlockPos base, BlockPos arm, Direction dir, boolean sticky) {
		BlockState b = level.getBlockState(base);
		if (b.hasProperty(PistonBaseBlock.EXTENDED)) level.setBlock(base, b.setValue(PistonBaseBlock.EXTENDED, true), Block.UPDATE_CLIENTS);
		BlockState head = Blocks.PISTON_HEAD.defaultBlockState().setValue(PistonHeadBlock.FACING, dir)
				.setValue(PistonHeadBlock.TYPE, sticky ? PistonType.STICKY : PistonType.DEFAULT).setValue(PistonHeadBlock.SHORT, false);
		set(level, arm, head, Block.UPDATE_ALL);
	}

	/** One wet sponge against a block that does not move: all its water comes back, the sponge breaks. */
	private static void crush(ServerLevel level, BlockPos base, Direction dir, boolean sticky) {
		MoistureSimulation sim = sim(level);
		BlockPos arm = base.relative(dir), wall = arm.relative(dir);
		BlockState sponge = level.getBlockState(arm);
		int u = sim.units(arm);
		sim.setUnits(arm, 0);
		extendBase(level, base, arm, dir, sticky);
		level.levelEvent(2001, arm, Block.getId(sponge));
		Block.popResource(level, arm, new ItemStack(Items.SPONGE));
		int levels = u / U;
		int left = release(level, List.of(arm), levels, Set.of(wall, base));
		left = intoCell(level, arm, left);
		// what found no room (and the fraction of a level) stays in the cell: it goes out through the head later
		sim.setUnits(arm, u - (levels - left) * U);
	}

	/** Two sponges pressed together: each gives back half its water, they become one double sponge. */
	private static void pressTwo(ServerLevel level, BlockPos base, Direction dir, boolean sticky) {
		MoistureSimulation sim = sim(level);
		BlockPos a = base.relative(dir), b = a.relative(dir), wall = b.relative(dir);
		int ua = sim.units(a), ub = sim.units(b);
		int levels = (ua / 2 + ub / 2) / U;
		int keep = ua + ub - levels * U;
		sim.setUnits(a, 0);
		sim.setUnits(b, keep);
		extendBase(level, base, a, dir, sticky);
		set(level, b, OcwoSponges.COMPRESSED_SPONGE.defaultBlockState().setValue(CompressedSpongeBlock.FACING, dir.getOpposite())
				.setValue(CompressedSpongeBlock.WET, keep >= U), Block.UPDATE_ALL);
		level.playSound(null, b, SoundEvents.WET_SPONGE_PLACE, SoundSource.BLOCKS, 1.0F, 0.8F);
		int left = release(level, List.of(b, a), levels, Set.of(wall, base));
		left = intoCell(level, a, left);
		if (left > 0) sim.setUnits(b, keep + left * U);
		updateLook(level, b);
	}

	/** Double sponge whose piston head went away: two sponges again, sharing its water, which absorb again. */
	public static void trySplit(ServerLevel level, BlockPos pos, BlockState state) {
		if (quiet) return;
		Direction f = state.getValue(CompressedSpongeBlock.FACING);
		BlockPos other = pos.relative(f);
		BlockState os = level.getBlockState(other);
		if (os.is(Blocks.PISTON_HEAD) || os.is(Blocks.MOVING_PISTON)) return;
		FluidState fs = level.getFluidState(other);
		if (!fs.isEmpty() && !isWater(fs)) return;
		if (!os.isAir() && !os.canBeReplaced()) return;
		MoistureSimulation sim = sim(level);
		int u = sim.units(pos);
		int half = u / 2;
		// water already in the freed cell (and what the head kept) goes into the sponge put there
		int there = sim.units(other);
		if (isWater(fs)) {
			there += fs.getAmount() * U;
			FluidUtils.setFluid(level, other, Fluids.WATER, 0, false);
		}
		sim.setUnits(other, there + half);
		sim.setUnits(pos, u - half);
		set(level, pos, Blocks.SPONGE.defaultBlockState(), Block.UPDATE_ALL);
		set(level, other, Blocks.SPONGE.defaultBlockState(), Block.UPDATE_ALL);
		level.playSound(null, pos, SoundEvents.SPONGE_PLACE, SoundSource.BLOCKS, 1.0F, 1.2F);
		absorb(level, pos);
		absorb(level, other);
		releaseSurplus(level, other, Set.of(pos));
		updateLook(level, pos);
		updateLook(level, other);
	}

	/**
	 * Sticky piston retracting with a sponge in front of its head that is held on the other side by a sticky element:
	 * the sponge stretches over two blocks instead of moving. Returns true if it did.
	 */
	public static boolean tryStretch(ServerLevel level, BlockPos base, Direction dir) {
		if (OcwoSponges.STRETCHED_SPONGE == null) return false;
		BlockPos arm = base.relative(dir), s = arm.relative(dir), anchor = s.relative(dir);
		if (!OcwoSponges.isSponge(level.getBlockState(s)) || !isStickyAnchor(level.getBlockState(anchor), dir)) return false;
		BlockState armState = level.getBlockState(arm);
		if (!armState.is(Blocks.PISTON_HEAD) && !armState.isAir()) return false;
		BlockState half = OcwoSponges.STRETCHED_SPONGE.defaultBlockState();
		set(level, arm, half.setValue(StretchedSpongeBlock.FACING, dir), Block.UPDATE_ALL);
		set(level, s, half.setValue(StretchedSpongeBlock.FACING, dir.getOpposite()), Block.UPDATE_ALL);
		level.playSound(null, s, SoundEvents.SLIME_BLOCK_PLACE, SoundSource.BLOCKS, 0.8F, 0.7F);
		absorb(level, s);
		absorb(level, arm);
		updateLook(level, s);
		updateLook(level, arm);
		return true;
	}

	/** The piston pushes a stretched sponge back into one block: one sponge, the surplus comes back around it. */
	private static void unstretch(ServerLevel level, BlockPos base, Direction dir, boolean sticky) {
		MoistureSimulation sim = sim(level);
		BlockPos arm = base.relative(dir), s = arm.relative(dir);
		int total = sim.units(arm) + sim.units(s);
		sim.setUnits(arm, 0);
		sim.setUnits(s, total);
		set(level, s, Blocks.SPONGE.defaultBlockState(), Block.UPDATE_ALL);
		extendBase(level, base, arm, dir, sticky);
		releaseSurplus(level, s, Set.of(arm, base));
		updateLook(level, s);
	}

	/** One half of a stretched sponge was destroyed: the other half is one sponge again (surplus given back). */
	public static void onStretchedRemoved(ServerLevel level, BlockPos pos, BlockState state) {
		if (quiet) return;
		MoistureSimulation sim = sim(level);
		BlockPos other = pos.relative(state.getValue(StretchedSpongeBlock.FACING));
		BlockState os = level.getBlockState(other);
		int u = sim.units(pos);
		if (OcwoSponges.isStretched(os) && os.getValue(StretchedSpongeBlock.FACING) == state.getValue(StretchedSpongeBlock.FACING).getOpposite()) {
			sim.setUnits(pos, 0);
			sim.setUnits(other, sim.units(other) + u);
			set(level, other, Blocks.SPONGE.defaultBlockState(), Block.UPDATE_ALL);
			releaseSurplus(level, other, Set.of(pos));
			updateLook(level, other);
		} else {
			onSpongeGone(level, pos, u);
		}
	}

	/**
	 * Before a retracting piston pulls a block into its head's cell: the water the head held goes around it first
	 * (vanilla replaces the head with air, which would delete it).
	 */
	public static void saveHeadWater(ServerLevel level, BlockPos base, Direction dir) {
		BlockPos arm = base.relative(dir);
		if (!level.getBlockState(arm).is(Blocks.PISTON_HEAD)) return;
		FluidState fs = level.getFluidState(arm);
		if (!isWater(fs)) return;
		int levels = fs.getAmount();
		FluidUtils.setFluid(level, arm, Fluids.WATER, 0, false);
		int left = release(level, List.of(arm), levels, Set.of(base, arm.relative(dir)));
		if (left > 0) sim(level).setUnits(arm, sim(level).units(arm) + left * U);
	}

	/**
	 * Blocks pushed or pulled by a piston carry their moisture (a wet sponge keeps its water): moved from each source
	 * cell to its destination before the move. The fixed water of the block stays accounted by the block change hook.
	 */
	public static void carryMoisture(ServerLevel level, List<BlockPos> toPush, Direction pushDirection) {
		if (toPush.isEmpty()) return;
		MoistureSimulation sim = sim(level);
		List<BlockPos> dest = new ArrayList<>(toPush.size());
		int[] carry = new int[toPush.size()];
		boolean any = false;
		for (int i = 0; i < toPush.size(); i++) {
			BlockPos p = toPush.get(i);
			dest.add(p.relative(pushDirection));
			MoistureProps props = MoistureData.get(level.getBlockState(p));
			if (props == null || !props.holdsMoisture()) continue;
			int u = sim.units(p);
			carry[i] = u - Math.min(u, props.fixedWater());
			any |= carry[i] > 0;
		}
		if (!any) return;
		for (int i = 0; i < toPush.size(); i++) {
			if (carry[i] > 0) sim.setUnits(toPush.get(i), sim.units(toPush.get(i)) - carry[i]);
		}
		for (int i = 0; i < toPush.size(); i++) {
			if (carry[i] > 0) sim.setUnits(dest.get(i), sim.units(dest.get(i)) + carry[i]);
		}
	}
}
