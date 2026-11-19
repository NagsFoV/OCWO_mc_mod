package io.github.nagsfov.ocwo.sponge;

import io.github.nagsfov.ocwo.fluids.CustomFluidTicks;
import io.github.nagsfov.ocwo.fluids.FluidUtils;
import io.github.nagsfov.ocwo.fluids.OcwoFluidGameRules;
import io.github.nagsfov.ocwo.fluids.WaterReserves;
import io.github.nagsfov.ocwo.moisture.Moisture;
import io.github.nagsfov.ocwo.moisture.MoistureData;
import io.github.nagsfov.ocwo.moisture.MoistureProps;
import io.github.nagsfov.ocwo.moisture.MoistureSimulation;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * OCWO sponges (03-humidite-et-etats-des-blocs.md, "Éponges"). Valentin's rules of 18 and 19/11/2026; the adjustable
 * choices are in {@link SpongeRules}. Water is conserved exactly everywhere (1 level = 16 units).
 * <ul>
 *   <li><b>A sponge is a bucket</b>: it holds 0 to 64 levels (128 for a stretched sponge, the two halves sharing them),
 *   stored in its cell(s); it exchanges water only with water blocks (absorption at once when placed or when a
 *   neighbour changes, through connected water up to 6 blocks away), never with moist ground. Adjacent sponges share
 *   their water evenly. It dries slowly in the open in hot, dry biomes (to the clouds).</li>
 *   <li><b>Items</b>: a sponge item carries its water (component {@code ocwo:sponge_water}); a broken sponge drops with
 *   it, a crushed one gives it back and drops dry; placed, the sponge gets it back.</li>
 *   <li><b>Stretched sponge</b> (two blocks): holds while a sticky element touches each end; otherwise it is one
 *   sponge again, in the half still held (none: where it was before), giving back what is over 64 levels. Breaking
 *   one half breaks both.</li>
 *   <li><b>Double sponge</b>: holds while a solid block touches each side along its axis; otherwise it splits into
 *   two sponges (the second in the freed cell; both free: on the side the piston head was).</li>
 *   <li>Pistons: see {@link SpongePistons}.</li>
 *   <li>Jumping on a full sponge squeezes 1 level out, a hard landing 4 (into the cell above, then beside).</li>
 * </ul>
 */
public final class SpongeLogic {

	private static final int U = Moisture.UNITS_PER_LEVEL;
	public static final int SPONGE_LEVELS = SpongeRules.SPONGE_LEVELS;
	private static final int ABSORB_DEPTH = 6;
	private static final int ABSORB_CELLS = 64;
	/** Fixed order: water goes down first, then sideways (deterministic, like the rest of the flow). */
	static final Direction[] ORDER = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP};

	/** true while this class changes blocks itself (no removal reaction, no moisture accounting, no checks). */
	private static boolean quiet;
	private static boolean absorbing, equalizing;
	/** Plain sponges removed and not dropped yet, per level: pos -> game time of the removal. */
	private static final Map<ServerLevel, Long2LongOpenHashMap> PENDING = new WeakHashMap<>();

	private SpongeLogic() {
	}

	public static boolean isQuiet() {
		return quiet;
	}

	static MoistureSimulation sim(ServerLevel level) {
		return CustomFluidTicks.get(level).moisture();
	}

	/** Capacity of one cell, in units. */
	public static int capacity(BlockState state) {
		MoistureProps p = MoistureData.get(state);
		return p != null && p.holdsMoisture() ? p.capacity() : SPONGE_LEVELS * U;
	}

	static boolean isWater(FluidState fs) {
		return !fs.isEmpty() && fs.getType().isSame(Fluids.WATER);
	}

	/** Sets a block without moisture accounting and without the sponges' own reactions. */
	static void set(ServerLevel level, BlockPos pos, BlockState state, int flags) {
		boolean was = quiet;
		quiet = true;
		try {
			sim(level).setBlockQuietly(pos, state, flags);
		} finally {
			quiet = was;
		}
	}

	static int units(ServerLevel level, BlockPos pos) {
		return sim(level).units(pos);
	}

	static void setUnits(ServerLevel level, BlockPos pos, int units) {
		sim(level).setUnits(pos, units);
	}

	// ------------------------------------------------------------------ a sponge as a bucket

	/** The other half of a stretched sponge (null if this half has lost it). */
	static BlockPos otherHalf(ServerLevel level, BlockPos pos, BlockState state) {
		if (!OcwoSponges.isStretched(state)) return null;
		Direction f = state.getValue(StretchedSpongeBlock.FACING);
		BlockPos other = pos.relative(f);
		BlockState os = level.getBlockState(other);
		return OcwoSponges.isStretched(os) && os.getValue(StretchedSpongeBlock.FACING) == f.getOpposite() ? other : null;
	}

	/** The cells of the sponge at pos: one, or both halves of a stretched sponge. */
	static List<BlockPos> cells(ServerLevel level, BlockPos pos, BlockState state) {
		BlockPos other = otherHalf(level, pos, state);
		return other == null ? List.of(pos) : List.of(pos, other);
	}

	/** Water of the sponge at pos (both halves of a stretched sponge), in units. */
	public static int poolUnits(ServerLevel level, BlockPos pos) {
		int sum = 0;
		for (BlockPos c : cells(level, pos, level.getBlockState(pos))) sum += units(level, c);
		return sum;
	}

	/** Capacity of the sponge at pos (128 levels for a stretched one), in units. */
	public static int poolCapacity(ServerLevel level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		return cells(level, pos, state).size() * capacity(state);
	}

	/** Sets the water of the sponge at pos (a stretched sponge shares it between its halves, in whole levels). */
	static void setPool(ServerLevel level, BlockPos pos, int total) {
		BlockState state = level.getBlockState(pos);
		List<BlockPos> c = cells(level, pos, state);
		if (c.size() == 1) {
			setUnits(level, pos, total);
			return;
		}
		// the half where the sponge was gets the odd level and the fraction
		boolean first = state.getValue(StretchedSpongeBlock.ORIGIN);
		int half = (total / U / 2) * U;
		setUnits(level, first ? c.get(1) : c.get(0), half);
		setUnits(level, first ? c.get(0) : c.get(1), total - half);
	}

	/** Absorbs at once the water around a sponge (plain or stretched: around both halves), up to its capacity. */
	public static void absorb(ServerLevel level, BlockPos pos) {
		if (absorbing || quiet) return;
		BlockState state = level.getBlockState(pos);
		if (!OcwoSponges.isSponge(state) && !OcwoSponges.isStretched(state)) return;
		List<BlockPos> origins = cells(level, pos, state);
		int pool = poolUnits(level, pos);
		int room = (poolCapacity(level, pos) - pool) / U;
		int taken = 0;
		if (room > 0) {
			absorbing = true;
			try {
				ArrayDeque<BlockPos> queue = new ArrayDeque<>();
				ArrayDeque<Integer> depth = new ArrayDeque<>();
				LongOpenHashSet seen = new LongOpenHashSet();
				for (BlockPos o : origins) {
					seen.add(o.asLong());
					queue.add(o);
					depth.add(0);
				}
				int cellsSeen = 0;
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
						if (++cellsSeen > ABSORB_CELLS) break search;
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
			setPool(level, pos, pool + taken * U);
			level.playSound(null, pos, SoundEvents.SPONGE_ABSORB, SoundSource.BLOCKS, 1.0F, 1.0F);
		}
		updateLook(level, pos);
		equalize(level, pos);
	}

	/** Wet look as soon as the sponge holds one water level (both halves of a stretched sponge alike). */
	public static void updateLook(ServerLevel level, BlockPos pos) {
		BlockState s0 = level.getBlockState(pos);
		boolean wet = poolUnits(level, pos) >= U;
		for (BlockPos c : cells(level, pos, s0)) {
			BlockState state = level.getBlockState(c);
			BlockState want = state;
			if (OcwoSponges.isSponge(state)) {
				want = (wet ? Blocks.WET_SPONGE : Blocks.SPONGE).defaultBlockState();
			} else if (OcwoSponges.isStretched(state)) {
				want = state.setValue(StretchedSpongeBlock.WET, wet);
			} else if (OcwoSponges.isCompressed(state)) {
				want = state.setValue(CompressedSpongeBlock.WET, units(level, c) >= U);
			}
			if (want != state) set(level, c, want, Block.UPDATE_ALL);
		}
	}

	/** Wet sponge placed where water evaporates (Nether): its water evaporates, as in vanilla. */
	public static void evaporate(ServerLevel level, BlockPos pos) {
		// the water goes up (Nether: to the clouds of the matching Overworld zone, see WaterReserves.toClouds)
		WaterReserves.toClouds(level, pos, units(level, pos));
		setUnits(level, pos, 0);
		set(level, pos, Blocks.SPONGE.defaultBlockState(), Block.UPDATE_ALL);
		level.levelEvent(2009, pos, 0);
		level.playSound(null, pos, SoundEvents.WET_SPONGE_DRIES, SoundSource.BLOCKS, 1.0F, (1.0F + level.getRandom().nextFloat() * 0.2F) * 0.7F);
	}

	// ------------------------------------------------------------------ sponges side by side share their water

	/**
	 * The sponges touching each other (plain or stretched, not compressed; at most {@link SpongeRules#GROUP_MAX}) share
	 * their water in proportion to their capacity, like communicating vessels: whole levels, the extra levels going one
	 * by one to the sponges nearest to origin (breadth first, fixed order), the fraction of a level to origin.
	 */
	public static void equalize(ServerLevel level, BlockPos origin) {
		if (!SpongeRules.EQUALIZE || quiet || equalizing) return;
		BlockState os = level.getBlockState(origin);
		if (!OcwoSponges.isSponge(os) && !OcwoSponges.isStretched(os)) return;
		List<List<BlockPos>> members = new ArrayList<>();
		LongOpenHashSet seen = new LongOpenHashSet();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		List<BlockPos> first = cells(level, origin, os);
		members.add(first);
		for (BlockPos c : first) {
			seen.add(c.asLong());
			queue.add(c);
		}
		while (!queue.isEmpty() && members.size() < SpongeRules.GROUP_MAX) {
			BlockPos cur = queue.poll();
			for (Direction d : ORDER) {
				BlockPos n = cur.relative(d);
				if (seen.contains(n.asLong()) || !level.isLoaded(n)) continue;
				BlockState ns = level.getBlockState(n);
				if (!OcwoSponges.isSponge(ns) && !OcwoSponges.isStretched(ns)) continue;
				List<BlockPos> m = cells(level, n, ns);
				for (BlockPos c : m) {
					seen.add(c.asLong());
					queue.add(c);
				}
				members.add(m);
				if (members.size() >= SpongeRules.GROUP_MAX) break;
			}
		}
		if (members.size() < 2) return;
		int n = members.size();
		long total = 0, capTotal = 0;
		long[] cap = new long[n];
		for (int i = 0; i < n; i++) {
			for (BlockPos c : members.get(i)) {
				total += units(level, c);
				cap[i] += capacity(level.getBlockState(c));
			}
			capTotal += cap[i];
		}
		long levels = total / U, fraction = total % U;
		long[] share = new long[n];
		long given = 0;
		for (int i = 0; i < n; i++) {
			share[i] = levels * cap[i] / capTotal;
			given += share[i];
		}
		for (int i = 0; given < levels; i = (i + 1) % n) {
			share[i]++;
			given++;
		}
		boolean changed = false;
		long[] want = new long[n];
		for (int i = 0; i < n; i++) {
			want[i] = share[i] * U + (i == 0 ? fraction : 0);
			long now = 0;
			for (BlockPos c : members.get(i)) now += units(level, c);
			if (now != want[i]) changed = true;
		}
		if (!changed) return;
		equalizing = true;
		try {
			for (int i = 0; i < n; i++) {
				setPool(level, members.get(i).get(0), (int) want[i]);
				updateLook(level, members.get(i).get(0));
			}
		} finally {
			equalizing = false;
		}
	}

	// ------------------------------------------------------------------ drying (random ticks of the moisture simulation)

	/**
	 * Random tick of a sponge cell: in the open, in a biome without precipitation and at least
	 * {@link SpongeRules#DRYING_MIN_TEMPERATURE} of base temperature, it dries slowly (to the clouds); a full sponge
	 * dries in about {@link SpongeRules#DRYING_TICKS_FOR_FULL} ticks whatever {@code ocwo:moisture_random_ticks} is.
	 */
	public static void randomTick(ServerLevel level, BlockPos pos, BlockState state) {
		if (OcwoFluidGameRules.SPONGE_DRYING != null && !level.getGameRules().get(OcwoFluidGameRules.SPONGE_DRYING)) return;
		int u = units(level, pos);
		if (u < U) return;
		var biome = level.getBiome(pos).value();
		if (biome.hasPrecipitation() || biome.getBaseTemperature() < SpongeRules.DRYING_MIN_TEMPERATURE) return;
		if (SpongeRules.DRYING_NEEDS_SKY && !level.canSeeSky(pos.above())) return;
		int n = OcwoFluidGameRules.MOISTURE_RANDOM_TICKS == null ? 3 : level.getGameRules().get(OcwoFluidGameRules.MOISTURE_RANDOM_TICKS);
		if (n <= 0) return;
		// a cell is drawn n times out of 4096 per tick: SPONGE_LEVELS levels in DRYING_TICKS_FOR_FULL ticks
		int perStep = (int) Math.max(1, Math.ceil(SPONGE_LEVELS * 4096.0 / ((double) SpongeRules.DRYING_TICKS_FOR_FULL * n)));
		int take = Math.min(perStep, u / U);
		setUnits(level, pos, u - take * U);
		WaterReserves.toClouds(level, pos, (long) take * U);
		if (OcwoSponges.isStretched(state)) setPool(level, pos, poolUnits(level, pos));
		updateLook(level, pos);
		equalize(level, pos);
	}

	// ------------------------------------------------------------------ giving water back

	/** A cell where water can go: air, or water that is not full (and not falling). */
	static boolean free(ServerLevel level, BlockPos p) {
		if (!level.isLoaded(p)) return false;
		BlockState s = level.getBlockState(p);
		FluidState fs = level.getFluidState(p);
		if (isWater(fs)) return fs.getAmount() < FluidUtils.MAX_LEVEL && !FluidUtils.isFalling(fs) && (s.getBlock() instanceof LiquidBlock || s.isAir());
		return fs.isEmpty() && s.isAir();
	}

	/**
	 * Gives {@code levels} water levels back, first into the start cells themselves (in order), then into the free cells
	 * reachable from them through free cells (breadth first, fixed order, at most {@link SpongeRules#RELEASE_CELLS}
	 * cells), never into the blocked cells. {@code dryRun}: changes nothing. Returns what found no room.
	 */
	static int releaseFrom(ServerLevel level, List<BlockPos> starts, int levels, Set<BlockPos> blocked, boolean dryRun) {
		if (levels <= 0) return 0;
		LongOpenHashSet visited = new LongOpenHashSet();
		for (BlockPos b : blocked) visited.add(b.asLong());
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		for (BlockPos s : starts) {
			if (visited.add(s.asLong()) && free(level, s)) queue.add(s);
		}
		int cells = 0;
		while (!queue.isEmpty() && levels > 0 && cells++ < SpongeRules.RELEASE_CELLS) {
			BlockPos cur = queue.poll();
			FluidState fs = level.getFluidState(cur);
			int have = isWater(fs) ? fs.getAmount() : 0;
			int put = Math.min(levels, FluidUtils.MAX_LEVEL - have);
			if (put > 0) {
				if (!dryRun) FluidUtils.setFluid(level, cur, Fluids.WATER, have + put, false);
				levels -= put;
			}
			for (Direction d : ORDER) {
				BlockPos n = cur.relative(d);
				if (!visited.add(n.asLong())) continue;
				if (free(level, n)) queue.add(n);
			}
		}
		return levels;
	}

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
		while (!queue.isEmpty() && levels > 0 && steps++ < SpongeRules.RELEASE_CELLS) {
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

	/** A sponge holding more than its capacity gives the surplus back around it (what finds no room stays in it). */
	static void releaseSurplus(ServerLevel level, BlockPos pos, Set<BlockPos> blocked) {
		int u = poolUnits(level, pos);
		int over = u - poolCapacity(level, pos);
		if (over <= 0) return;
		int levels = (over + U - 1) / U;
		int left = release(level, cells(level, pos, level.getBlockState(pos)), levels, blocked);
		setPool(level, pos, u - (levels - left) * U);
	}

	/** Gives units back around pos at once (what finds no room stays in the cell, released later). */
	static void giveBack(ServerLevel level, BlockPos pos, int units) {
		int levels = units / U;
		int left = release(level, List.of(pos), levels, Set.of());
		setUnits(level, pos, units - (levels - left) * U);
	}

	// ------------------------------------------------------------------ removal and items

	/** The block a player in creative mode is breaking (level, position, game time), or none. */
	private static ServerLevel creativeLevel;
	private static long creativePos, creativeTime = Long.MIN_VALUE;

	/**
	 * A player in creative mode starts (or ends) breaking the block at pos. Water is conserved in survival and
	 * adventure only: in creative mode, the water of a broken sponge disappears, as in vanilla (Valentin, 19/11/2026).
	 */
	public static void creativeBreak(ServerLevel level, BlockPos pos, boolean start) {
		if (start) {
			creativeLevel = level;
			creativePos = pos.asLong();
			creativeTime = level.getGameTime();
		} else {
			creativeLevel = null;
		}
	}

	/** Is the block at pos being broken by a player in creative mode (this tick)? */
	static boolean brokenInCreative(ServerLevel level, BlockPos pos) {
		return SpongeRules.CREATIVE_BREAK_DELETES_WATER && creativeLevel == level && creativePos == pos.asLong() && creativeTime == level.getGameTime();
	}

	/**
	 * A plain sponge went away (mined, burnt, exploded…, not moved by a piston): its water goes into the item it drops
	 * ({@link #onDrop}); if it drops none (creative mode, explosion), the water comes back around its cell at the end
	 * of the tick ({@link #tick}).
	 */
	public static void onSpongeGone(ServerLevel level, BlockPos pos, int units) {
		if (quiet || units < U) return;
		if (brokenInCreative(level, pos)) {
			setUnits(level, pos, 0); // creative mode: its water disappears, nothing drops
			return;
		}
		if (OcwoSponges.SPONGE_WATER == null) {
			giveBack(level, pos, units);
			return;
		}
		PENDING.computeIfAbsent(level, k -> new Long2LongOpenHashMap()).put(pos.asLong(), level.getGameTime());
	}

	/** A block drops an item at pos (Block.popResource): a plain sponge's drop takes the water of the removed sponge. */
	public static ItemStack onDrop(ServerLevel level, BlockPos pos, ItemStack stack) {
		if (!OcwoSponges.isSpongeItem(stack) || OcwoSponges.SPONGE_WATER == null || stack.has(OcwoSponges.SPONGE_WATER)) return stack;
		Long2LongOpenHashMap pending = PENDING.get(level);
		if (pending != null && pending.containsKey(pos.asLong())) {
			pending.remove(pos.asLong()); // removed first, then dropped (a player breaking it)
		} else if (!OcwoSponges.isSponge(level.getBlockState(pos))) {
			return stack;
		} // else: dropped first, then removed (Level.destroyBlock, explosions): the sponge is still there
		int u = units(level, pos);
		int levels = Math.min(u / U, SPONGE_LEVELS);
		if (levels <= 0) return stack;
		setUnits(level, pos, u - levels * U);
		return OcwoSponges.spongeItem(levels, stack.getCount());
	}

	/** End of each level tick: the water of the sponges that dropped no item comes back around their cells. */
	public static void tick(ServerLevel level) {
		Long2LongOpenHashMap pending = PENDING.get(level);
		if (pending == null || pending.isEmpty()) return;
		long now = level.getGameTime();
		var it = pending.long2LongEntrySet().fastIterator();
		List<BlockPos> due = new ArrayList<>();
		while (it.hasNext()) {
			var e = it.next();
			if (e.getLongValue() < now) {
				due.add(BlockPos.of(e.getLongKey()));
				it.remove();
			}
		}
		for (BlockPos p : due) {
			BlockState s = level.getBlockState(p);
			if (OcwoSponges.isAnySponge(s)) continue; // a sponge is there again: its water is its own
			int u = units(level, p);
			if (u >= U) giveBack(level, p, u);
		}
	}

	/** Drops a sponge item holding {@code levels} levels (without the item component: gives them back around). */
	static void dropSponge(ServerLevel level, BlockPos pos, int levels) {
		if (levels > 0 && OcwoSponges.SPONGE_WATER == null) {
			int left = release(level, List.of(pos), levels, Set.of());
			setUnits(level, pos, units(level, pos) + left * U);
			levels = 0;
		}
		Block.popResource(level, pos, OcwoSponges.spongeItem(levels, 1));
	}

	/** A sponge was placed from an item: it gets the item's water back (Block.setPlacedBy). */
	public static void onPlaced(ServerLevel level, BlockPos pos, BlockState state, ItemStack stack) {
		if (!OcwoSponges.isSponge(state)) return;
		int levels = OcwoSponges.itemLevels(stack);
		if (levels <= 0) return;
		if (level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES, pos)) {
			WaterReserves.toClouds(level, pos, (long) levels * U); // the Nether: it dries at once
			return;
		}
		setUnits(level, pos, units(level, pos) + levels * U);
		updateLook(level, pos);
		releaseSurplus(level, pos, Set.of());
		equalize(level, pos);
	}

	// ------------------------------------------------------------------ stretched sponge: held at both ends

	/** A sticky element at p facing {@code toward} (slime, honey, a sticky piston's head or face; a moving piston). */
	static boolean stickyAt(ServerLevel level, BlockPos p, Direction toward) {
		BlockState s = level.getBlockState(p);
		if (s.is(Blocks.MOVING_PISTON)) return true; // a piston moving there: checked again when it lands
		if (s.is(Blocks.SLIME_BLOCK) || s.is(Blocks.HONEY_BLOCK)) return true;
		if (s.is(Blocks.PISTON_HEAD)) {
			return s.getValue(PistonHeadBlock.TYPE) == PistonType.STICKY && s.getValue(PistonHeadBlock.FACING) == toward;
		}
		return s.is(Blocks.STICKY_PISTON) && s.getValue(PistonBaseBlock.FACING) == toward && !s.getValue(PistonBaseBlock.EXTENDED);
	}

	/**
	 * A stretched sponge holds while a sticky element touches each end. Otherwise it becomes one sponge in the half
	 * still held (none: the half where it was before it was stretched). Returns true if it changed.
	 */
	public static boolean checkStretched(ServerLevel level, BlockPos pos) {
		if (quiet) return false;
		BlockState state = level.getBlockState(pos);
		if (!OcwoSponges.isStretched(state)) return false;
		BlockPos other = otherHalf(level, pos, state);
		if (other == null) {
			// a lone half (should not happen): a sponge again
			collapse(level, pos, null, pos, Set.of());
			return true;
		}
		Direction f = state.getValue(StretchedSpongeBlock.FACING);
		boolean heldHere = stickyAt(level, pos.relative(f.getOpposite()), f);
		boolean heldThere = stickyAt(level, other.relative(f), f.getOpposite());
		if (heldHere && heldThere) return false;
		BlockPos target;
		if (heldHere) target = pos;
		else if (heldThere) target = other;
		else if (SpongeRules.STRETCHED_ALONE_BACK_TO_ORIGIN) target = state.getValue(StretchedSpongeBlock.ORIGIN) ? pos : other;
		else target = pos;
		collapse(level, pos, other, target, Set.of());
		return true;
	}

	/**
	 * The stretched sponge (a, b) becomes one sponge at target, with all its water; the surplus over 64 comes out
	 * (never into the blocked cells).
	 */
	static void collapse(ServerLevel level, BlockPos a, BlockPos b, BlockPos target, Set<BlockPos> blocked) {
		BlockPos gone = target.equals(a) ? b : a;
		int pool = units(level, a) + (b != null ? units(level, b) : 0);
		if (gone != null) {
			setUnits(level, gone, 0);
			set(level, gone, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}
		setUnits(level, target, pool);
		set(level, target, Blocks.SPONGE.defaultBlockState(), Block.UPDATE_ALL);
		level.playSound(null, target, SoundEvents.SLIME_BLOCK_PLACE, SoundSource.BLOCKS, 0.8F, 1.2F);
		releaseSurplus(level, target, blocked);
		updateLook(level, target);
		equalize(level, target);
	}

	/** One half of a stretched sponge was broken: the other half breaks too; one sponge drops (64 levels at most). */
	public static void onStretchedRemoved(ServerLevel level, BlockPos pos, BlockState state) {
		if (quiet) return;
		Direction f = state.getValue(StretchedSpongeBlock.FACING);
		BlockPos other = pos.relative(f);
		BlockState os = level.getBlockState(other);
		boolean pair = OcwoSponges.isStretched(os) && os.getValue(StretchedSpongeBlock.FACING) == f.getOpposite();
		int pool = units(level, pos) + (pair ? units(level, other) : 0);
		if (pair) {
			setUnits(level, other, 0);
			set(level, other, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			level.levelEvent(2001, other, Block.getId(os));
		}
		if (brokenInCreative(level, pos)) {
			setUnits(level, pos, 0); // creative mode: its water disappears, nothing drops
			return;
		}
		breakInto(level, pos, pair ? other : null, pool);
	}

	/** A broken stretched sponge: one item with up to 64 levels, the rest around both cells. */
	static void breakInto(ServerLevel level, BlockPos a, BlockPos b, int pool) {
		int levels = pool / U;
		int item = Math.min(levels, SPONGE_LEVELS);
		int rest = levels - item;
		setUnits(level, a, pool - levels * U); // the fraction of a level stays in the cell (released later)
		if (b != null) setUnits(level, b, 0);
		int left = release(level, b != null ? List.of(a, b) : List.of(a), rest, Set.of());
		if (left > 0) setUnits(level, a, units(level, a) + left * U);
		dropSponge(level, a, item);
	}

	// ------------------------------------------------------------------ double sponge: held on both sides of its axis

	/** Holds a double sponge on one side: a solid block (not air, not replaceable, not a fluid), or a moving piston. */
	static boolean holds(ServerLevel level, BlockPos p) {
		BlockState s = level.getBlockState(p);
		if (s.is(Blocks.MOVING_PISTON)) return true;
		if (s.isAir() || s.getBlock() instanceof LiquidBlock) return false;
		return !s.canBeReplaced();
	}

	/**
	 * A double sponge holds while a solid block touches each side along its axis; otherwise it splits into two
	 * sponges, the second in the freed cell (both free: on the side where the piston head was).
	 */
	public static void checkCompressed(ServerLevel level, BlockPos pos) {
		if (quiet) return;
		BlockState state = level.getBlockState(pos);
		if (!OcwoSponges.isCompressed(state)) return;
		Direction f = state.getValue(CompressedSpongeBlock.FACING);
		BlockPos head = pos.relative(f), wall = pos.relative(f.getOpposite());
		boolean h = holds(level, head), w = holds(level, wall);
		if (h && w) return;
		split(level, pos, !h ? head : wall);
	}

	/** The double sponge at pos splits: two sponges sharing its water, the second at other (its water goes in). */
	static void split(ServerLevel level, BlockPos pos, BlockPos other) {
		int u = units(level, pos);
		int half = (u / U / 2) * U;
		int there = units(level, other);
		FluidState fs = level.getFluidState(other);
		if (isWater(fs)) {
			there += fs.getAmount() * U;
			FluidUtils.setFluid(level, other, Fluids.WATER, 0, false);
		}
		setUnits(level, other, there + half);
		setUnits(level, pos, u - half);
		set(level, pos, Blocks.SPONGE.defaultBlockState(), Block.UPDATE_ALL);
		set(level, other, Blocks.SPONGE.defaultBlockState(), Block.UPDATE_ALL);
		level.playSound(null, pos, SoundEvents.SPONGE_PLACE, SoundSource.BLOCKS, 1.0F, 1.2F);
		releaseSurplus(level, other, Set.of(pos));
		releaseSurplus(level, pos, Set.of(other));
		updateLook(level, pos);
		updateLook(level, other);
		absorb(level, pos);
		absorb(level, other);
	}

	/** A double sponge was broken: two sponges drop, each with its half of the water. */
	public static void onCompressedRemoved(ServerLevel level, BlockPos pos) {
		if (quiet) return;
		if (brokenInCreative(level, pos)) {
			setUnits(level, pos, 0); // creative mode: its water disappears, nothing drops
			return;
		}
		breakCompressed(level, pos);
	}

	static void breakCompressed(ServerLevel level, BlockPos pos) {
		int u = units(level, pos);
		int levels = u / U;
		int a = levels / 2, b = levels - a;
		setUnits(level, pos, u - levels * U);
		dropSponge(level, pos, Math.min(a, SPONGE_LEVELS));
		dropSponge(level, pos, Math.min(b, SPONGE_LEVELS));
		int over = Math.max(0, a - SPONGE_LEVELS) + Math.max(0, b - SPONGE_LEVELS);
		if (over > 0) giveBack(level, pos, units(level, pos) + over * U);
	}

	// ------------------------------------------------------------------ pistons: stretching

	/**
	 * Sticky piston retracting with a sponge in front of its head that is held on the other side by a sticky element:
	 * the sponge stretches over two blocks instead of moving. Returns true if it did.
	 */
	public static boolean tryStretch(ServerLevel level, BlockPos base, Direction dir) {
		if (OcwoSponges.STRETCHED_SPONGE == null) return false;
		BlockPos arm = base.relative(dir), s = arm.relative(dir), anchor = s.relative(dir);
		if (!OcwoSponges.isSponge(level.getBlockState(s)) || !stickyAt(level, anchor, dir.getOpposite())) return false;
		BlockState armState = level.getBlockState(arm);
		if (!armState.is(Blocks.PISTON_HEAD) && !armState.isAir()) return false;
		BlockState half = OcwoSponges.STRETCHED_SPONGE.defaultBlockState();
		set(level, arm, half.setValue(StretchedSpongeBlock.FACING, dir).setValue(StretchedSpongeBlock.ORIGIN, false), Block.UPDATE_ALL);
		set(level, s, half.setValue(StretchedSpongeBlock.FACING, dir.getOpposite()).setValue(StretchedSpongeBlock.ORIGIN, true), Block.UPDATE_ALL);
		level.playSound(null, s, SoundEvents.SLIME_BLOCK_PLACE, SoundSource.BLOCKS, 0.8F, 0.7F);
		setPool(level, s, poolUnits(level, s));
		absorb(level, s);
		updateLook(level, s);
		return true;
	}

	/**
	 * Before a retracting piston pulls a block into its head's cell: the water the head held goes around it first
	 * (vanilla replaces the head with air, which would delete it). Only for worlds where the head took water.
	 */
	public static void saveHeadWater(ServerLevel level, BlockPos base, Direction dir) {
		BlockPos arm = base.relative(dir);
		if (!level.getBlockState(arm).is(Blocks.PISTON_HEAD)) return;
		FluidState fs = level.getFluidState(arm);
		if (!isWater(fs)) return;
		int levels = fs.getAmount();
		FluidUtils.setFluid(level, arm, Fluids.WATER, 0, false);
		int left = release(level, List.of(arm), levels, Set.of(base, arm.relative(dir)));
		if (left > 0) setUnits(level, arm, units(level, arm) + left * U);
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

	// ------------------------------------------------------------------ squeezing it by jumping on it

	/**
	 * An entity lands on a sponge: a jump squeezes 1 level out of a full sponge (never under full - 1), a hard landing
	 * 4 levels (never under full - 4); a stretched sponge counts its 128 levels. The water goes into the cell above,
	 * then beside the sponge; what finds no room stays in the sponge.
	 */
	public static void onFallOn(ServerLevel level, BlockState state, BlockPos pos, double fallDistance) {
		if (fallDistance < SpongeRules.JUMP_MIN_FALL) return;
		if (!OcwoSponges.isSponge(state) && !OcwoSponges.isStretched(state)) return;
		int pool = poolUnits(level, pos);
		int full = poolCapacity(level, pos) / U;
		boolean hard = fallDistance > SpongeRules.HARD_FALL;
		int squeeze = hard ? SpongeRules.LANDING_LEVELS : SpongeRules.JUMP_LEVELS;
		int out = Math.min(squeeze, pool / U - (full - squeeze));
		if (out <= 0) return;
		List<BlockPos> cells = new ArrayList<>();
		cells.add(pos.above());
		for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) cells.add(pos.relative(d));
		int placed = 0;
		for (BlockPos c : cells) {
			if (placed >= out) break;
			if (!free(level, c)) continue;
			FluidState fs = level.getFluidState(c);
			int have = isWater(fs) ? fs.getAmount() : 0;
			int put = Math.min(out - placed, FluidUtils.MAX_LEVEL - have);
			FluidUtils.setFluid(level, c, Fluids.WATER, have + put, false);
			placed += put;
		}
		if (placed == 0) return;
		setPool(level, pos, pool - placed * U);
		level.playSound(null, pos, SoundEvents.WET_SPONGE_DRIES, SoundSource.BLOCKS, 0.6F, 1.4F);
		updateLook(level, pos);
		equalize(level, pos);
	}
}
