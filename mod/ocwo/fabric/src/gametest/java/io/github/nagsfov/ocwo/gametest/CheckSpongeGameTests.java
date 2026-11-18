package io.github.nagsfov.ocwo.gametest;

import io.github.nagsfov.ocwo.fluids.CustomFluidTicks;
import io.github.nagsfov.ocwo.moisture.Moisture;
import io.github.nagsfov.ocwo.moisture.MoistureSimulation;
import io.github.nagsfov.ocwo.sponge.CompressedSpongeBlock;
import io.github.nagsfov.ocwo.sponge.OcwoSponges;
import io.github.nagsfov.ocwo.sponge.StretchedSpongeBlock;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * OCWO sponges (03-humidite-et-etats-des-blocs.md, "Éponges"): the sponge stores the water it absorbs; a piston
 * crushes a wet sponge against a block that does not move (water given back around it, then through the head),
 * presses two sponges into a double sponge (half their water each) that splits again, stretches a sponge held on the
 * other side (twice the capacity) and gives back the surplus when it is one block again. Water is conserved exactly
 * (liquid + moisture) in every case.
 * <p>
 * Layout: an obsidian shell x 0..6, y 0..5, z 0..4 with an air cavity x 1..5, y 1..4, z 1..3; the piston at (1,2,2)
 * faces east, powered by a redstone block put in the shell at (0,2,2).
 */
public class CheckSpongeGameTests {

	private static final int U = Moisture.UNITS_PER_LEVEL;
	private static final BlockPos PISTON = new BlockPos(1, 2, 2), POWER = new BlockPos(0, 2, 2);
	private static final BlockPos A = new BlockPos(2, 2, 2), B = new BlockPos(3, 2, 2), C = new BlockPos(4, 2, 2);

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

	/** Liquid water levels in the cavity (the piston head's cell included). */
	private static int liquid(GameTestHelper h) {
		int sum = 0;
		for (int x = 1; x <= 5; x++)
			for (int y = 1; y <= 4; y++)
				for (int z = 1; z <= 3; z++) sum += WaterCount.liquidLevels(h, new BlockPos(x, y, z));
		return sum;
	}

	private static void conserved(GameTestHelper h, long start, String when) {
		long now = total(h);
		System.out.println("[OCWO-TEST] sponge " + when + ": water " + start + " -> " + now + " units, liquid " + liquid(h) + " levels");
		if (now != start) h.fail("water not conserved " + when + ": " + start + " -> " + now);
	}

	private static boolean is(GameTestHelper h, BlockPos rel, Block b) {
		return h.getBlockState(rel).is(b);
	}

	/** Placed in water, a sponge stores what it absorbs: the water is not destroyed. */
	@GameTest(maxTicks = 100, padding = 16)
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
			if (u != 30 * 8 * U - liquid(h) * U) h.fail("the sponge holds " + u + " units, liquid left " + liquid(h) + " levels");
			if (u != 64 * U) h.fail("the sponge should be full (64 levels): " + u / U);
			if (!is(h, new BlockPos(3, 3, 2), Blocks.WET_SPONGE)) h.fail("a sponge holding water should look wet");
			conserved(h, start, "absorbing");
			h.succeed();
		});
	}

	/** A pushed sponge keeps its water (moisture moves with the block). */
	@GameTest(maxTicks = 100, padding = 16)
	public void pushedSpongeKeepsWater(GameTestHelper h) {
		shell(h, false);
		sponge(h, A, 30);
		long start = total(h);
		power(h, true);
		h.runAfterDelay(10, () -> {
			if (!is(h, B, Blocks.WET_SPONGE)) h.fail("the sponge was not pushed: " + h.getBlockState(B));
			if (WaterCount.moisture(h, B) != 30 * U) h.fail("the pushed sponge holds " + WaterCount.moisture(h, B) + " units instead of " + 30 * U);
			if (WaterCount.moisture(h, A) != 0) h.fail("water left behind: " + WaterCount.moisture(h, A));
			conserved(h, start, "pushed");
			h.succeed();
		});
	}

	/** Wet sponge against a block that does not move: crushed, all its water around it, dropped as a sponge item. */
	@GameTest(maxTicks = 100, padding = 16)
	public void crushGivesWaterBack(GameTestHelper h) {
		shell(h, false);
		sponge(h, A, 40);
		put(h, B, Blocks.OBSIDIAN.defaultBlockState());
		long start = total(h);
		power(h, true);
		h.runAfterDelay(10, () -> {
			if (!is(h, A, Blocks.PISTON_HEAD)) h.fail("the piston did not extend: " + h.getBlockState(A));
			h.assertItemEntityPresent(Items.SPONGE);
			if (liquid(h) != 40) h.fail("40 levels should be back as water: " + liquid(h));
			if (WaterCount.moisture(h, A) != 0) h.fail("water left in the head's cell: " + WaterCount.moisture(h, A));
			conserved(h, start, "crushed");
			h.succeed();
		});
	}

	/** No room around: the head takes 8 levels, the rest stays as moisture of its cell (released later), nothing lost. */
	@GameTest(maxTicks = 100, padding = 16)
	public void crushWithoutRoom(GameTestHelper h) {
		shell(h, false);
		for (BlockPos p : new BlockPos[]{A.above(), A.below(), A.north(), A.south()}) put(h, p, Blocks.OBSIDIAN.defaultBlockState());
		sponge(h, A, 20);
		put(h, B, Blocks.OBSIDIAN.defaultBlockState());
		long start = total(h);
		power(h, true);
		h.runAfterDelay(10, () -> {
			if (!is(h, A, Blocks.PISTON_HEAD)) h.fail("the piston did not extend: " + h.getBlockState(A));
			// 8 levels in the head (some may go on into the piston body, which holds water too)
			if (liquid(h) != 8) h.fail("the piston should hold 8 levels: " + liquid(h));
			if (WaterCount.moisture(h, A) != 12 * U) h.fail("12 levels should wait in the cell: " + WaterCount.moisture(h, A) / U);
			conserved(h, start, "crushed without room");
			h.succeed();
		});
	}

	/** Two sponges pressed: half their water each, one double sponge; the piston retracts: two sponges again. */
	@GameTest(maxTicks = 200, padding = 16)
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

	/** Sticky piston pulling a sponge held by slime: stretched over two blocks, twice the capacity; pushed back: surplus out. */
	@GameTest(maxTicks = 200, padding = 16)
	public void stretchedSponge(GameTestHelper h) {
		shell(h, true);
		power(h, true);
		long[] start = new long[1];
		h.startSequence()
				.thenExecuteAfter(5, () -> {
					if (!is(h, A, Blocks.PISTON_HEAD)) h.fail("the sticky piston did not extend");
					for (int x = 1; x <= 5; x++)
						for (int z = 1; z <= 3; z++) put(h, new BlockPos(x, 1, z), Blocks.WATER.defaultBlockState());
					sponge(h, B, 50);
					put(h, C, Blocks.SLIME_BLOCK.defaultBlockState());
					start[0] = total(h);
					power(h, false);
				})
				.thenExecuteAfter(10, () -> {
					BlockState a = h.getBlockState(A), b = h.getBlockState(B);
					if (!OcwoSponges.isStretched(a) || !OcwoSponges.isStretched(b)) h.fail("not stretched: " + a + " " + b);
					if (a.getValue(StretchedSpongeBlock.FACING) != Direction.EAST || b.getValue(StretchedSpongeBlock.FACING) != Direction.WEST) {
						h.fail("halves facing the wrong way: " + a + " " + b);
					}
					int held = WaterCount.moisture(h, A) + WaterCount.moisture(h, B);
					System.out.println("[OCWO-TEST] stretched sponge holds " + held / U + " levels");
					if (held <= 64 * U) h.fail("a stretched sponge should hold more than a sponge: " + held / U + " levels");
					conserved(h, start[0], "stretched");
					power(h, true);
				})
				.thenExecuteAfter(10, () -> {
					if (!is(h, A, Blocks.PISTON_HEAD)) h.fail("the piston did not push the stretched sponge back: " + h.getBlockState(A));
					if (!OcwoSponges.isSponge(h.getBlockState(B))) h.fail("not one sponge again: " + h.getBlockState(B));
					if (WaterCount.moisture(h, B) > 64 * U) h.fail("the surplus was not given back: " + WaterCount.moisture(h, B) / U + " levels");
					conserved(h, start[0], "one block again");
				})
				.thenSucceed();
	}

	/** One half of a stretched sponge destroyed: the other half is a sponge again, surplus given back, no item. */
	@GameTest(maxTicks = 200, padding = 16)
	public void stretchedHalfDestroyed(GameTestHelper h) {
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
					// give the stretched sponge more than one sponge holds
					sim(h).setUnits(h.absolutePos(A), 30 * U);
					start[0] = total(h);
					ServerLevel level = h.getLevel();
					level.destroyBlock(h.absolutePos(A), true);
				})
				.thenExecuteAfter(5, () -> {
					if (!OcwoSponges.isSponge(h.getBlockState(B))) h.fail("the other half is not a sponge again: " + h.getBlockState(B));
					if (WaterCount.moisture(h, B) > 64 * U) h.fail("the surplus was not given back: " + WaterCount.moisture(h, B) / U);
					h.assertItemEntityNotPresent(Items.SPONGE);
					conserved(h, start[0], "half destroyed");
				})
				.thenSucceed();
	}
}
