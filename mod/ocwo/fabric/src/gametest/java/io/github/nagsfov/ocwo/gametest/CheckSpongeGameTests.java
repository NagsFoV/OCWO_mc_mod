package io.github.nagsfov.ocwo.gametest;

import io.github.nagsfov.ocwo.fluids.CustomFluidTicks;
import io.github.nagsfov.ocwo.moisture.Moisture;
import io.github.nagsfov.ocwo.moisture.MoistureSimulation;
import io.github.nagsfov.ocwo.sponge.CompressedSpongeBlock;
import io.github.nagsfov.ocwo.sponge.OcwoSponges;
import io.github.nagsfov.ocwo.sponge.SpongeLogic;
import io.github.nagsfov.ocwo.sponge.StretchedSpongeBlock;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * OCWO sponges (03-humidite-et-etats-des-blocs.md, "Éponges"), Valentin's rules of 18 and 19/11/2026: one test per
 * rule, each checking that water is conserved exactly (liquid + moisture + what went up + what sponge items carry).
 * <p>
 * Layout: an obsidian shell x 0..6, y 0..5, z 0..4 with an air cavity x 1..5, y 1..4, z 1..3; the piston at (1,2,2)
 * faces east, powered by a redstone block put in the shell at (0,2,2). Environment "sponges": they do not dry.
 */
public class CheckSpongeGameTests {

	private static final String ENV = "ocwo-gametest:sponges";
	private static final int U = Moisture.UNITS_PER_LEVEL;
	private static final BlockPos PISTON = new BlockPos(1, 2, 2), POWER = new BlockPos(0, 2, 2);
	private static final BlockPos A = new BlockPos(2, 2, 2), B = new BlockPos(3, 2, 2), C = new BlockPos(4, 2, 2), D = new BlockPos(5, 2, 2);

	private static MoistureSimulation sim(GameTestHelper h) {
		return CustomFluidTicks.get(h.getLevel()).moisture();
	}

	private static void put(GameTestHelper h, BlockPos rel, BlockState s) {
		h.getLevel().setBlock(h.absolutePos(rel), s, Block.UPDATE_ALL);
	}

	private static void shell(GameTestHelper h, boolean sticky) {
		for (int x = 0; x <= 6; x++)
			for (int y = 0; y <= 5; y++)
				for (int z = 0; z <= 4; z++) {
					boolean inside = x >= 1 && x <= 5 && y >= 1 && y <= 4 && z >= 1 && z <= 3;
					put(h, new BlockPos(x, y, z), inside ? Blocks.AIR.defaultBlockState() : Blocks.OBSIDIAN.defaultBlockState());
				}
		put(h, PISTON, (sticky ? Blocks.STICKY_PISTON : Blocks.PISTON).defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
	}

	/** A sponge holding {@code levels} water levels (its moisture first, then the block). */
	private static void sponge(GameTestHelper h, BlockPos rel, int levels) {
		sim(h).setUnits(h.absolutePos(rel), levels * U);
		put(h, rel, Blocks.SPONGE.defaultBlockState());
	}

	private static void power(GameTestHelper h, boolean on) {
		put(h, POWER, (on ? Blocks.REDSTONE_BLOCK : Blocks.OBSIDIAN).defaultBlockState());
	}

	private static long total(GameTestHelper h) {
		return WaterCount.units(h, 0, 0, 0, 6, 5, 4);
	}

	/** Liquid water levels in the cavity. */
	private static int liquid(GameTestHelper h) {
		int sum = 0;
		for (int x = 1; x <= 5; x++)
			for (int y = 1; y <= 4; y++)
				for (int z = 1; z <= 3; z++) sum += WaterCount.liquidLevels(h, new BlockPos(x, y, z));
		return sum;
	}

	private static int pool(GameTestHelper h, BlockPos rel) {
		return SpongeLogic.poolUnits(h.getLevel(), h.absolutePos(rel)) / U;
	}

	private static void conserved(GameTestHelper h, long start, String when) {
		long now = total(h);
		System.out.println("[OCWO-TEST] sponge " + when + ": water " + start + " -> " + now + " units, liquid " + liquid(h) + " levels");
		if (now != start) h.fail("water not conserved " + when + ": " + start + " -> " + now);
	}

	private static boolean is(GameTestHelper h, BlockPos rel, Block b) {
		return h.getBlockState(rel).is(b);
	}

	/** The sponge items lying in the cavity. */
	private static List<ItemStack> items(GameTestHelper h) {
		BlockPos a = h.absolutePos(new BlockPos(0, 0, 0)), b = h.absolutePos(new BlockPos(7, 6, 5));
		AABB box = new AABB(Math.min(a.getX(), b.getX()), a.getY(), Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()), b.getY(), Math.max(a.getZ(), b.getZ()));
		// one entry per item (identical items lying together merge into one stack)
		return h.getLevel().getEntitiesOfClass(ItemEntity.class, box).stream().map(ItemEntity::getItem).filter(OcwoSponges::isSpongeItem)
				.flatMap(s -> java.util.stream.Stream.generate(() -> s.copyWithCount(1)).limit(s.getCount())).toList();
	}

	/** The two halves of a stretched sponge, a facing b, b being where the sponge was (written without neighbour updates). */
	private static void stretched(GameTestHelper h, BlockPos a, BlockPos b, Direction ab, int levels) {
		BlockState half = OcwoSponges.STRETCHED_SPONGE.defaultBlockState();
		h.getLevel().setBlock(h.absolutePos(a), half.setValue(StretchedSpongeBlock.FACING, ab).setValue(StretchedSpongeBlock.ORIGIN, false), Block.UPDATE_CLIENTS);
		h.getLevel().setBlock(h.absolutePos(b), half.setValue(StretchedSpongeBlock.FACING, ab.getOpposite()).setValue(StretchedSpongeBlock.ORIGIN, true), Block.UPDATE_CLIENTS);
		sim(h).setUnits(h.absolutePos(a), levels / 2 * U);
		sim(h).setUnits(h.absolutePos(b), (levels - levels / 2) * U);
	}

	// ------------------------------------------------------------------ the sponge as a bucket

	/** Placed in water, a sponge stores what it absorbs: the water is not destroyed. */
	@GameTest(environment = ENV, maxTicks = 100, padding = 16)
	public void absorbStoresWater(GameTestHelper h) {
		shell(h, false);
		put(h, PISTON, Blocks.AIR.defaultBlockState());
		for (int x = 1; x <= 5; x++)
			for (int z = 1; z <= 3; z++) {
				put(h, new BlockPos(x, 1, z), Blocks.WATER.defaultBlockState());
				put(h, new BlockPos(x, 2, z), Blocks.WATER.defaultBlockState());
			}
		long start = total(h);
		put(h, new BlockPos(3, 3, 2), Blocks.SPONGE.defaultBlockState());
		h.runAfterDelay(5, () -> {
			int u = WaterCount.moisture(h, new BlockPos(3, 3, 2));
			if (u != 64 * U) h.fail("the sponge should be full (64 levels): " + u / U);
			if (!is(h, new BlockPos(3, 3, 2), Blocks.WET_SPONGE)) h.fail("a sponge holding water should look wet");
			conserved(h, start, "absorbing");
			h.succeed();
		});
	}

	/** A sponge on wet ground takes nothing from it and gives it nothing: it exchanges only with water blocks. */
	@GameTest(environment = "ocwo-gametest:sponges_fast", maxTicks = 400, padding = 16)
	public void noExchangeWithGround(GameTestHelper h) {
		shell(h, false);
		put(h, PISTON, Blocks.AIR.defaultBlockState());
		for (int x = 1; x <= 5; x++)
			for (int z = 1; z <= 3; z++) put(h, new BlockPos(x, 1, z), Blocks.DIRT.defaultBlockState());
		sim(h).setUnits(h.absolutePos(new BlockPos(3, 1, 2)), 80);
		sponge(h, new BlockPos(3, 2, 2), 30);
		sim(h).activate(h.absolutePos(new BlockPos(3, 2, 2)));
		long start = total(h);
		h.runAfterDelay(300, () -> {
			if (pool(h, new BlockPos(3, 2, 2)) != 30) h.fail("the sponge exchanged with the ground: " + pool(h, new BlockPos(3, 2, 2)) + " levels");
			conserved(h, start, "on wet ground");
			h.succeed();
		});
	}

	/** Two sponges side by side share their water evenly. */
	@GameTest(environment = ENV, maxTicks = 100, padding = 16)
	public void spongesShareTheirWater(GameTestHelper h) {
		shell(h, false);
		put(h, PISTON, Blocks.AIR.defaultBlockState());
		sponge(h, B, 50);
		long start = total(h) + 10L * U;
		sponge(h, C, 10);
		h.runAfterDelay(3, () -> {
			if (pool(h, B) != 30 || pool(h, C) != 30) h.fail("not shared evenly: " + pool(h, B) + " and " + pool(h, C));
			conserved(h, start, "shared");
			h.succeed();
		});
	}

	/** In a hot, dry biome, in the open, a sponge dries slowly; its water goes up to the clouds. */
	@GameTest(environment = "ocwo-gametest:sponge_drying", maxTicks = 300, padding = 16)
	public void spongeDriesInTheDesert(GameTestHelper h) {
		sponge(h, new BlockPos(1, 1, 1), 64);
		sim(h).activate(h.absolutePos(new BlockPos(1, 1, 1)));
		long start = WaterCount.units(h, 1, 1, 1, 1, 1, 1);
		h.runAfterDelay(200, () -> {
			int left = pool(h, new BlockPos(1, 1, 1));
			long now = WaterCount.units(h, 1, 1, 1, 1, 1, 1);
			System.out.println("[OCWO-TEST] sponge drying: 64 -> " + left + " levels in 200 ticks (random ticks 1024)");
			if (left >= 64) h.fail("the sponge did not dry in the desert");
			if (now != start) h.fail("water not conserved while drying: " + start + " -> " + now);
			h.succeed();
		});
	}

	/** Jumping on a full sponge squeezes 1 level out (never under 63); a hard landing 4 (never under 60). */
	@GameTest(environment = ENV, maxTicks = 100, padding = 16)
	public void jumpingSqueezesWaterOut(GameTestHelper h) {
		shell(h, false);
		put(h, PISTON, Blocks.AIR.defaultBlockState());
		sponge(h, B, 64);
		sponge(h, D, 64); // not touching B
		long start = total(h);
		ServerLevel level = h.getLevel();
		var pig = h.spawn(net.minecraft.world.entity.EntityTypes.PIG, new BlockPos(3, 3, 2));
		BlockPos jump = h.absolutePos(B), land = h.absolutePos(D);
		for (int i = 0; i < 2; i++) {
			level.getBlockState(jump).getBlock().fallOn(level, level.getBlockState(jump), jump, pig, 1.25);
			level.getBlockState(land).getBlock().fallOn(level, level.getBlockState(land), land, pig, 5.0);
		}
		pig.discard();
		if (pool(h, B) != 63) h.fail("two jumps should leave 63 levels: " + pool(h, B));
		if (pool(h, D) != 60) h.fail("two hard landings should leave 60 levels: " + pool(h, D));
		if (WaterCount.liquidLevels(h, B.above()) != 1) h.fail("the jump's level should be above the sponge: " + WaterCount.liquidLevels(h, B.above()));
		if (WaterCount.liquidLevels(h, D.above()) != 4) h.fail("the landing's 4 levels should be above the sponge: " + WaterCount.liquidLevels(h, D.above()));
		conserved(h, start, "squeezed");
		h.succeed();
	}

	// ------------------------------------------------------------------ items carry their water

	/** A broken sponge drops with its water; placed again, it gets it back. */
	@GameTest(environment = ENV, maxTicks = 100, padding = 16)
	public void itemKeepsItsWater(GameTestHelper h) {
		shell(h, false);
		put(h, PISTON, Blocks.AIR.defaultBlockState());
		sponge(h, B, 40);
		long start = total(h);
		h.getLevel().destroyBlock(h.absolutePos(B), true);
		h.runAfterDelay(2, () -> {
			List<ItemStack> items = items(h);
			if (items.size() != 1 || OcwoSponges.itemLevels(items.get(0)) != 40 || !items.get(0).is(Items.WET_SPONGE)) {
				h.fail("expected one wet sponge item with 40 levels: " + items + " " + items.stream().map(OcwoSponges::itemLevels).toList());
			}
			if (WaterCount.moisture(h, B) != 0) h.fail("water left in the cell: " + WaterCount.moisture(h, B));
			conserved(h, start, "dropped");
			// placed again
			ItemStack stack = items.get(0).copy();
			h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(B)).inflate(4)).forEach(ItemEntity::discard);
			put(h, B, Blocks.WET_SPONGE.defaultBlockState());
			BlockState s = h.getLevel().getBlockState(h.absolutePos(B));
			s.getBlock().setPlacedBy(h.getLevel(), h.absolutePos(B), s, null, stack);
			if (pool(h, B) != 40) h.fail("the placed sponge should hold 40 levels: " + pool(h, B));
			conserved(h, start, "placed again");
			h.succeed();
		});
	}

	/** A wet sponge dried in a furnace: its water goes up as steam. */
	@GameTest(environment = ENV, maxTicks = 400, padding = 16)
	public void furnaceSendsWaterUp(GameTestHelper h) {
		shell(h, false);
		put(h, PISTON, Blocks.AIR.defaultBlockState());
		put(h, B, Blocks.FURNACE.defaultBlockState());
		var furnace = (net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(B));
		furnace.setItem(0, OcwoSponges.spongeItem(20, 1));
		furnace.setItem(1, new ItemStack(Items.COAL));
		long start = WaterCount.blockUnits(h, 0, 0, 0, 6, 5, 4) + 20L * U;
		h.succeedWhen(() -> {
			ItemStack out = furnace.getItem(2);
			h.assertTrue(out.is(Items.SPONGE), "not dried yet");
			long now = total(h);
			h.assertTrue(now == start, "water not conserved by the furnace: " + start + " -> " + now);
			System.out.println("[OCWO-TEST] sponge furnace: 20 levels went up as steam");
		});
	}

	// ------------------------------------------------------------------ pistons: crushing

	/** Wet sponge against a block that does not move: crushed, its water out through its 4 sides, dropped dry. */
	@GameTest(environment = ENV, maxTicks = 100, padding = 16)
	public void crushGivesWaterBack(GameTestHelper h) {
		shell(h, false);
		sponge(h, A, 40);
		put(h, B, Blocks.OBSIDIAN.defaultBlockState());
		long start = total(h);
		power(h, true);
		h.runAfterDelay(10, () -> {
			if (!is(h, A, Blocks.PISTON_HEAD)) h.fail("the piston did not extend: " + h.getBlockState(A));
			List<ItemStack> items = items(h);
			if (items.size() != 1 || !items.get(0).is(Items.SPONGE) || OcwoSponges.itemLevels(items.get(0)) != 0) h.fail("expected one dry sponge item: " + items);
			if (liquid(h) != 40) h.fail("40 levels should be back as water: " + liquid(h));
			if (WaterCount.liquidLevels(h, A) != 0) h.fail("water went into the piston head");
			conserved(h, start, "crushed");
			h.succeed();
		});
	}

	/** A wet sponge whose 4 side cells are all closed: the piston does not push at all. */
	@GameTest(environment = ENV, maxTicks = 100, padding = 16)
	public void noRoomNoPush(GameTestHelper h) {
		shell(h, false);
		for (BlockPos p : new BlockPos[]{A.above(), A.below(), A.north(), A.south()}) put(h, p, Blocks.OBSIDIAN.defaultBlockState());
		sponge(h, A, 20);
		put(h, B, Blocks.OBSIDIAN.defaultBlockState());
		long start = total(h);
		power(h, true);
		h.runAfterDelay(10, () -> {
			if (!OcwoSponges.isSponge(h.getBlockState(A)) || pool(h, A) != 20) h.fail("the sponge should be untouched: " + h.getBlockState(A) + " " + pool(h, A));
			if (h.getBlockState(PISTON).getValue(PistonBaseBlock.EXTENDED)) h.fail("the piston should not extend");
			conserved(h, start, "no room");
			h.succeed();
		});
	}

	/** A dry sponge against a block that does not move is crushed too. */
	@GameTest(environment = ENV, maxTicks = 100, padding = 16)
	public void drySpongeIsCrushed(GameTestHelper h) {
		shell(h, false);
		sponge(h, A, 0);
		put(h, B, Blocks.OBSIDIAN.defaultBlockState());
		power(h, true);
		h.runAfterDelay(10, () -> {
			if (!is(h, A, Blocks.PISTON_HEAD)) h.fail("the piston did not extend: " + h.getBlockState(A));
			if (items(h).size() != 1) h.fail("expected one sponge item: " + items(h));
			h.succeed();
		});
	}

	/** A sponge never pushes another block: in front of a stone it can push, it is crushed and the stone stays. */
	@GameTest(environment = ENV, maxTicks = 100, padding = 16)
	public void spongeNeverPushesABlock(GameTestHelper h) {
		shell(h, false);
		sponge(h, A, 30);
		put(h, B, Blocks.STONE.defaultBlockState());
		long start = total(h);
		power(h, true);
		h.runAfterDelay(10, () -> {
			if (!is(h, A, Blocks.PISTON_HEAD)) h.fail("the piston did not extend: " + h.getBlockState(A));
			if (!is(h, B, Blocks.STONE) || !is(h, C, Blocks.AIR)) h.fail("the stone was pushed: " + h.getBlockState(B) + " " + h.getBlockState(C));
			if (liquid(h) != 30) h.fail("30 levels should be back as water: " + liquid(h));
			conserved(h, start, "crushed against a stone");
			h.succeed();
		});
	}

	/** Slime stuck to a sponge moves with it. */
	@GameTest(environment = ENV, maxTicks = 100, padding = 16)
	public void slimeMovesWithTheSponge(GameTestHelper h) {
		shell(h, false);
		sponge(h, A, 30);
		put(h, B, Blocks.SLIME_BLOCK.defaultBlockState());
		long start = total(h);
		power(h, true);
		h.runAfterDelay(10, () -> {
			if (!OcwoSponges.isSponge(h.getBlockState(B)) || !is(h, C, Blocks.SLIME_BLOCK)) h.fail("sponge and slime did not move: " + h.getBlockState(B) + " " + h.getBlockState(C));
			if (pool(h, B) != 30) h.fail("the moved sponge holds " + pool(h, B));
			conserved(h, start, "moved with slime");
			h.succeed();
		});
	}

	/** A sponge pushed against a double sponge is crushed; the double sponge stays. */
	@GameTest(environment = ENV, maxTicks = 100, padding = 16)
	public void spongeAgainstDoubleIsCrushed(GameTestHelper h) {
		shell(h, false);
		put(h, B.north(), Blocks.OBSIDIAN.defaultBlockState());
		put(h, B.south(), Blocks.OBSIDIAN.defaultBlockState());
		sim(h).setUnits(h.absolutePos(B), 20 * U);
		put(h, B, OcwoSponges.COMPRESSED_SPONGE.defaultBlockState().setValue(CompressedSpongeBlock.FACING, Direction.NORTH).setValue(CompressedSpongeBlock.WET, true));
		sponge(h, A, 10);
		long start = total(h);
		power(h, true);
		h.runAfterDelay(10, () -> {
			if (!is(h, A, Blocks.PISTON_HEAD)) h.fail("the piston did not extend: " + h.getBlockState(A));
			if (!OcwoSponges.isCompressed(h.getBlockState(B)) || WaterCount.moisture(h, B) != 20 * U) h.fail("the double sponge changed: " + h.getBlockState(B));
			conserved(h, start, "crushed against a double sponge");
			h.succeed();
		});
	}

	// ------------------------------------------------------------------ double sponge

	/** Two sponges pressed: half their water each, one double sponge; the piston retracts: two sponges again. */
	@GameTest(environment = ENV, maxTicks = 200, padding = 16)
	public void doubleSponge(GameTestHelper h) {
		shell(h, false);
		sponge(h, A, 24);
		sponge(h, B, 40);
		put(h, C, Blocks.OBSIDIAN.defaultBlockState());
		long start = total(h);
		power(h, true);
		h.startSequence()
				.thenExecuteAfter(10, () -> {
					if (!is(h, A, Blocks.PISTON_HEAD)) h.fail("the piston did not extend: " + h.getBlockState(A));
					BlockState d = h.getBlockState(B);
					if (!OcwoSponges.isCompressed(d)) h.fail("no double sponge: " + d);
					if (d.getValue(CompressedSpongeBlock.FACING) != Direction.WEST) h.fail("double sponge facing " + d.getValue(CompressedSpongeBlock.FACING));
					if (WaterCount.moisture(h, B) != 32 * U) h.fail("the double sponge should keep 32 levels: " + WaterCount.moisture(h, B) / (double) U);
					if (liquid(h) != 32) h.fail("32 levels should be back as water: " + liquid(h));
					conserved(h, start, "pressed together");
					power(h, false);
				})
				.thenExecuteAfter(10, () -> {
					if (!OcwoSponges.isSponge(h.getBlockState(A)) || !OcwoSponges.isSponge(h.getBlockState(B))) {
						h.fail("the double sponge did not split: " + h.getBlockState(A) + " " + h.getBlockState(B));
					}
					conserved(h, start, "split");
				})
				.thenSucceed();
	}

	/** The block a double sponge is pressed against goes away: two sponges, the second in that block's place. */
	@GameTest(environment = ENV, maxTicks = 200, padding = 16)
	public void doubleSpongeWallRemoved(GameTestHelper h) {
		shell(h, false);
		sponge(h, A, 24);
		sponge(h, B, 40);
		put(h, C, Blocks.OBSIDIAN.defaultBlockState());
		long start = total(h);
		power(h, true);
		h.startSequence()
				.thenExecuteAfter(10, () -> {
					if (!OcwoSponges.isCompressed(h.getBlockState(B))) h.fail("no double sponge: " + h.getBlockState(B));
					put(h, C, Blocks.AIR.defaultBlockState());
				})
				.thenExecuteAfter(3, () -> {
					if (!OcwoSponges.isSponge(h.getBlockState(B)) || !OcwoSponges.isSponge(h.getBlockState(C))) h.fail("not two sponges at B and C: " + h.getBlockState(B) + " " + h.getBlockState(C));
					if (!is(h, A, Blocks.PISTON_HEAD)) h.fail("the piston head should still be there: " + h.getBlockState(A));
					conserved(h, start, "wall removed");
				})
				.thenSucceed();
	}

	/** A double sponge pressed again along its axis breaks: two sponges drop, each with its half of the water. */
	@GameTest(environment = ENV, maxTicks = 200, padding = 16)
	public void doubleSpongePressedAgainBreaks(GameTestHelper h) {
		shell(h, false);
		sponge(h, A, 24);
		sponge(h, B, 40);
		put(h, C, Blocks.OBSIDIAN.defaultBlockState());
		long[] start = {0};
		power(h, true);
		h.startSequence()
				.thenExecuteAfter(10, () -> {
					if (!OcwoSponges.isCompressed(h.getBlockState(B))) h.fail("no double sponge: " + h.getBlockState(B));
					// a second piston on the other side, facing it
					put(h, C, Blocks.PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.WEST));
					start[0] = total(h);
					put(h, D, Blocks.REDSTONE_BLOCK.defaultBlockState());
				})
				.thenExecuteAfter(10, () -> {
					List<ItemStack> items = items(h);
					List<Integer> levels = items.stream().map(OcwoSponges::itemLevels).sorted().toList();
					if (!levels.equals(List.of(16, 16))) h.fail("expected two sponges of 16 levels: " + levels);
					if (!is(h, B, Blocks.PISTON_HEAD)) h.fail("the second piston did not extend: " + h.getBlockState(B));
					conserved(h, start[0], "pressed again");
				})
				.thenSucceed();
	}

	/** A double sponge pushed across its axis moves; held on both sides there, it stays double. */
	@GameTest(environment = ENV, maxTicks = 100, padding = 16)
	public void doubleSpongePushedAcross(GameTestHelper h) {
		shell(h, false);
		for (BlockPos p : new BlockPos[]{A.north(), A.south(), B.north(), B.south()}) put(h, p, Blocks.OBSIDIAN.defaultBlockState());
		sim(h).setUnits(h.absolutePos(A), 20 * U);
		put(h, A, OcwoSponges.COMPRESSED_SPONGE.defaultBlockState().setValue(CompressedSpongeBlock.FACING, Direction.NORTH).setValue(CompressedSpongeBlock.WET, true));
		long start = total(h);
		power(h, true);
		h.runAfterDelay(10, () -> {
			if (!OcwoSponges.isCompressed(h.getBlockState(B)) || WaterCount.moisture(h, B) != 20 * U) h.fail("the double sponge did not move with its water: " + h.getBlockState(B));
			conserved(h, start, "moved across");
			h.succeed();
		});
	}

	/** A double sponge pushed across its axis to where nothing holds it splits there into two sponges. */
	@GameTest(environment = ENV, maxTicks = 100, padding = 16)
	public void doubleSpongePushedOutSplits(GameTestHelper h) {
		shell(h, false);
		put(h, A.north(), Blocks.OBSIDIAN.defaultBlockState());
		put(h, A.south(), Blocks.OBSIDIAN.defaultBlockState());
		sim(h).setUnits(h.absolutePos(A), 20 * U);
		put(h, A, OcwoSponges.COMPRESSED_SPONGE.defaultBlockState().setValue(CompressedSpongeBlock.FACING, Direction.NORTH).setValue(CompressedSpongeBlock.WET, true));
		long start = total(h);
		power(h, true);
		h.runAfterDelay(10, () -> {
			// both sides free: the second sponge goes where the head was (north)
			if (!OcwoSponges.isSponge(h.getBlockState(B)) || !OcwoSponges.isSponge(h.getBlockState(B.north()))) h.fail("did not split at B and north of it: " + h.getBlockState(B) + " " + h.getBlockState(B.north()));
			conserved(h, start, "pushed out");
			h.succeed();
		});
	}

	// ------------------------------------------------------------------ stretched sponge

	/** Sticky piston pulling a sponge held by slime: stretched over two blocks, 128 levels; pushed back: surplus out. */
	@GameTest(environment = ENV, maxTicks = 200, padding = 16)
	public void stretchedSponge(GameTestHelper h) {
		shell(h, true);
		power(h, true);
		long[] start = new long[1];
		h.startSequence()
				.thenExecuteAfter(5, () -> {
					if (!is(h, A, Blocks.PISTON_HEAD)) h.fail("the sticky piston did not extend");
					// water touching only the half that will be the head's cell
					put(h, A.below(), Blocks.WATER.defaultBlockState());
					put(h, new BlockPos(2, 1, 1), Blocks.WATER.defaultBlockState());
					put(h, new BlockPos(2, 1, 3), Blocks.WATER.defaultBlockState());
					sponge(h, B, 60);
					put(h, C, Blocks.SLIME_BLOCK.defaultBlockState());
					start[0] = total(h);
					power(h, false);
				})
				.thenExecuteAfter(10, () -> {
					BlockState a = h.getBlockState(A), b = h.getBlockState(B);
					if (!OcwoSponges.isStretched(a) || !OcwoSponges.isStretched(b)) h.fail("not stretched: " + a + " " + b);
					int held = pool(h, A);
					System.out.println("[OCWO-TEST] stretched sponge holds " + held + " levels");
					if (held <= 64) h.fail("the stretched sponge should have absorbed through its other half: " + held + " levels");
					conserved(h, start[0], "stretched");
					power(h, true);
				})
				.thenExecuteAfter(10, () -> {
					if (!is(h, A, Blocks.PISTON_HEAD)) h.fail("the piston did not push the stretched sponge back: " + h.getBlockState(A));
					if (!OcwoSponges.isSponge(h.getBlockState(B))) h.fail("not one sponge again: " + h.getBlockState(B));
					if (pool(h, B) > 64) h.fail("the surplus was not given back: " + pool(h, B) + " levels");
					conserved(h, start[0], "one block again");
				})
				.thenSucceed();
	}

	/** Without the slime, the stretched sponge is one sponge again in the half the piston still holds. */
	@GameTest(environment = ENV, maxTicks = 200, padding = 16)
	public void stretchedLosesTheSlime(GameTestHelper h) {
		stretchedThenRemove(h, C, A);
	}

	/** Without the piston, it is one sponge again in the half the slime still holds. */
	@GameTest(environment = ENV, maxTicks = 200, padding = 16)
	public void stretchedLosesThePiston(GameTestHelper h) {
		stretchedThenRemove(h, PISTON, B);
	}

	private static void stretchedThenRemove(GameTestHelper h, BlockPos removed, BlockPos expected) {
		shell(h, true);
		power(h, true);
		long[] start = new long[1];
		h.startSequence()
				.thenExecuteAfter(5, () -> {
					sponge(h, B, 64);
					put(h, C, Blocks.SLIME_BLOCK.defaultBlockState());
					power(h, false);
				})
				.thenExecuteAfter(10, () -> {
					if (!OcwoSponges.isStretched(h.getBlockState(A))) h.fail("not stretched: " + h.getBlockState(A));
					sim(h).setUnits(h.absolutePos(A), 62 * U); // 94 levels in all: more than one sponge holds
					start[0] = total(h);
					put(h, removed, Blocks.AIR.defaultBlockState());
				})
				.thenExecuteAfter(3, () -> {
					BlockPos other = expected.equals(A) ? B : A;
					// the freed half's cell may take some of the water given back
					if (!OcwoSponges.isSponge(h.getBlockState(expected)) || OcwoSponges.isAnySponge(h.getBlockState(other))) {
						h.fail("expected one sponge at " + expected + ": " + h.getBlockState(A) + " " + h.getBlockState(B));
					}
					if (pool(h, expected) > 64) h.fail("the surplus was not given back: " + pool(h, expected));
					conserved(h, start[0], "one end released");
				})
				.thenSucceed();
	}

	/** Held at neither end any more: it goes back to its cell before stretching. */
	@GameTest(environment = ENV, maxTicks = 100, padding = 16)
	public void stretchedHeldByNothing(GameTestHelper h) {
		shell(h, false);
		put(h, PISTON, Blocks.AIR.defaultBlockState());
		stretched(h, A, B, Direction.EAST, 80);
		long start = total(h);
		// nothing sticky at either end: a neighbour change makes it check
		put(h, C, Blocks.STONE.defaultBlockState());
		h.runAfterDelay(3, () -> {
			if (!OcwoSponges.isSponge(h.getBlockState(B)) || OcwoSponges.isAnySponge(h.getBlockState(A))) h.fail("not back at B: " + h.getBlockState(A) + " " + h.getBlockState(B));
			if (pool(h, B) > 64) h.fail("the surplus was not given back: " + pool(h, B));
			conserved(h, start, "held by nothing");
			h.succeed();
		});
	}

	/** Breaking one half of a stretched sponge breaks both: one sponge drops (64 levels at most), the rest comes out. */
	@GameTest(environment = ENV, maxTicks = 200, padding = 16)
	public void stretchedBrokenBreaksBoth(GameTestHelper h) {
		shell(h, true);
		power(h, true);
		long[] start = new long[1];
		h.startSequence()
				.thenExecuteAfter(5, () -> {
					sponge(h, B, 64);
					put(h, C, Blocks.SLIME_BLOCK.defaultBlockState());
					power(h, false);
				})
				.thenExecuteAfter(10, () -> {
					if (!OcwoSponges.isStretched(h.getBlockState(A))) h.fail("not stretched: " + h.getBlockState(A));
					sim(h).setUnits(h.absolutePos(A), 62 * U); // 94 levels in all
					start[0] = total(h);
					h.getLevel().destroyBlock(h.absolutePos(A), true);
				})
				.thenExecuteAfter(3, () -> {
					if (OcwoSponges.isAnySponge(h.getBlockState(A)) || OcwoSponges.isAnySponge(h.getBlockState(B))) h.fail("both halves should be gone: " + h.getBlockState(A) + " " + h.getBlockState(B));
					List<ItemStack> items = items(h);
					if (items.size() != 1 || OcwoSponges.itemLevels(items.get(0)) != 64) h.fail("expected one sponge of 64 levels: " + items.stream().map(OcwoSponges::itemLevels).toList());
					if (liquid(h) != 30) h.fail("30 levels should come out: " + liquid(h));
					conserved(h, start[0], "broken");
				})
				.thenSucceed();
	}

	/** A stretched sponge pulled again by a sticky piston while still held on the other side breaks. */
	@GameTest(environment = ENV, maxTicks = 200, padding = 16)
	public void stretchedStretchedAgainBreaks(GameTestHelper h) {
		shell(h, true);
		power(h, true);
		long[] start = new long[1];
		h.startSequence()
				.thenExecuteAfter(5, () -> {
					sponge(h, B, 40);
					put(h, C, Blocks.SLIME_BLOCK.defaultBlockState());
					power(h, false);
				})
				.thenExecuteAfter(10, () -> {
					if (!OcwoSponges.isStretched(h.getBlockState(A))) h.fail("not stretched: " + h.getBlockState(A));
					// a second sticky piston, extended, its head holding the other end instead of the slime
					put(h, new BlockPos(5, 3, 2), Blocks.REDSTONE_BLOCK.defaultBlockState());
					h.getLevel().setBlock(h.absolutePos(D), Blocks.STICKY_PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.WEST)
							.setValue(PistonBaseBlock.EXTENDED, true), Block.UPDATE_CLIENTS);
					h.getLevel().setBlock(h.absolutePos(C), Blocks.PISTON_HEAD.defaultBlockState().setValue(PistonHeadBlock.FACING, Direction.WEST)
							.setValue(PistonHeadBlock.TYPE, PistonType.STICKY), Block.UPDATE_CLIENTS);
					start[0] = total(h);
				})
				.thenExecuteAfter(2, () -> put(h, new BlockPos(5, 3, 2), Blocks.AIR.defaultBlockState()))
				.thenExecuteAfter(10, () -> {
					if (OcwoSponges.isAnySponge(h.getBlockState(A)) || OcwoSponges.isAnySponge(h.getBlockState(B))) h.fail("the stretched sponge should be broken: " + h.getBlockState(A) + " " + h.getBlockState(B));
					List<ItemStack> items = items(h);
					if (items.size() != 1 || OcwoSponges.itemLevels(items.get(0)) != 40) h.fail("expected one sponge of 40 levels: " + items.stream().map(OcwoSponges::itemLevels).toList());
					conserved(h, start[0], "stretched again");
				})
				.thenSucceed();
	}

	/**
	 * Valentin's line: sticky piston> / sponge / slime / stretched / stretched / <sticky piston. The left piston pushes:
	 * extended piston / head / sponge / slime / sponge / <sticky piston (the slime pushes the stretched sponge back into
	 * one block).
	 */
	@GameTest(environment = ENV, maxTicks = 100, padding = 16)
	public void valentinsLine(GameTestHelper h) {
		for (int x = 0; x <= 8; x++)
			for (int y = 0; y <= 4; y++)
				for (int z = 0; z <= 4; z++) {
					boolean inside = x >= 1 && x <= 7 && y >= 1 && y <= 3 && z >= 1 && z <= 3;
					put(h, new BlockPos(x, y, z), inside ? Blocks.AIR.defaultBlockState() : Blocks.OBSIDIAN.defaultBlockState());
				}
		BlockPos p1 = new BlockPos(1, 2, 2), s = new BlockPos(2, 2, 2), l = new BlockPos(3, 2, 2), t1 = new BlockPos(4, 2, 2), t2 = new BlockPos(5, 2, 2), p2 = new BlockPos(6, 2, 2);
		put(h, p1, Blocks.STICKY_PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.EAST));
		put(h, p2, Blocks.STICKY_PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING, Direction.WEST));
		sponge(h, s, 10);
		put(h, l, Blocks.SLIME_BLOCK.defaultBlockState());
		stretched(h, t1, t2, Direction.EAST, 100);
		long start = WaterCount.units(h, 0, 0, 0, 8, 4, 4);
		put(h, new BlockPos(0, 2, 2), Blocks.REDSTONE_BLOCK.defaultBlockState());
		h.runAfterDelay(10, () -> {
			boolean ok = h.getBlockState(p1).getValue(PistonBaseBlock.EXTENDED) && is(h, s, Blocks.PISTON_HEAD)
					&& OcwoSponges.isSponge(h.getBlockState(l)) && is(h, t1, Blocks.SLIME_BLOCK)
					&& OcwoSponges.isSponge(h.getBlockState(t2)) && is(h, p2, Blocks.STICKY_PISTON);
			if (!ok) h.fail("line: " + h.getBlockState(p1) + " / " + h.getBlockState(s) + " / " + h.getBlockState(l) + " / " + h.getBlockState(t1) + " / "
					+ h.getBlockState(t2) + " / " + h.getBlockState(p2));
			if (pool(h, t2) > 64) h.fail("the surplus was not given back: " + pool(h, t2));
			long now = WaterCount.units(h, 0, 0, 0, 8, 4, 4);
			if (now != start) h.fail("water not conserved in the line: " + start + " -> " + now);
			System.out.println("[OCWO-TEST] sponge line: OK, water " + start + " -> " + now);
			h.succeed();
		});
	}
}
