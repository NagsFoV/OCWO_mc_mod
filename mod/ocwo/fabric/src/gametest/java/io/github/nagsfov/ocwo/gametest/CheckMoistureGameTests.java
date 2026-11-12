package io.github.nagsfov.ocwo.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * OCWO block moisture scenarios (03-humidite-et-etats-des-blocs.md), with the default material values.
 * Every scenario is enclosed in obsidian (watertight) and checks at every tick that liquid water + moisture
 * never changes.
 */
public class CheckMoistureGameTests {

	// ------------------------------------------------------------------ helpers

	private static void fill(GameTestHelper h, int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
		for (int x = x0; x <= x1; x++)
			for (int y = y0; y <= y1; y++)
				for (int z = z0; z <= z1; z++)
					h.setBlock(new BlockPos(x, y, z), state);
	}

	private static void fill(GameTestHelper h, int x0, int y0, int z0, int x1, int y1, int z1, Block block) {
		fill(h, x0, y0, z0, x1, y1, z1, block.defaultBlockState());
	}

	/** Obsidian box (watertight shell) with an empty inside. */
	private static void box(GameTestHelper h, int x1, int y1, int z1) {
		fill(h, 0, 0, 0, x1, y1, z1, Blocks.OBSIDIAN);
		fill(h, 1, 1, 1, x1 - 1, y1 - 1, z1 - 1, Blocks.AIR);
	}

	private static long liquid(GameTestHelper h, int x0, int y0, int z0, int x1, int y1, int z1) {
		long sum = 0;
		for (int x = x0; x <= x1; x++)
			for (int y = y0; y <= y1; y++)
				for (int z = z0; z <= z1; z++)
					sum += WaterCount.liquidLevels(h, new BlockPos(x, y, z));
		return sum;
	}

	private static void conservation(GameTestHelper h, int x1, int y1, int z1) {
		long initial = WaterCount.units(h, 0, 0, 0, x1, y1, z1);
		h.onEachTick(() -> {
			long now = WaterCount.units(h, 0, 0, 0, x1, y1, z1);
			if (now != initial) h.fail("Water not conserved: " + initial + " units at start, " + now + " now");
		});
	}

	private static void report(GameTestHelper h, String test, String detail) {
		System.out.println("[OCWO-TEST] " + test + " OK at tick " + h.getTick() + " (" + detail + ")");
	}

	/**
	 * Pool | wall (1 block thick, x=4) | dry chamber, inside an obsidian box 9x7x5.
	 * Pool: x=1..3, y=1..5, z=1..3 (full of water). Chamber: x=5..7.
	 */
	private static void poolAndWall(GameTestHelper h, BlockState wall) {
		box(h, 8, 6, 4);
		fill(h, 4, 1, 1, 4, 5, 3, wall);
		fill(h, 1, 1, 1, 3, 5, 3, Blocks.WATER);
	}

	// ------------------------------------------------------------------ scenarios

	/** A cobblestone wall soaks up the pool and seeps into the dry chamber behind it. */
	@GameTest(maxTicks = 3000, padding = 24)
	public void cobblestoneWallSeeps(GameTestHelper h) {
		poolAndWall(h, Blocks.COBBLESTONE.defaultBlockState());
		conservation(h, 8, 6, 4);
		h.succeedWhen(() -> {
			long seeped = liquid(h, 5, 1, 1, 7, 5, 3);
			h.assertTrue(seeped > 0, "No water seeped through the cobblestone wall yet");
			report(h, "cobblestoneWallSeeps", seeped + " levels seeped, wall moisture at (4,1,2) = "
					+ WaterCount.moisture(h, new BlockPos(4, 1, 2)) + " units");
		});
	}

	/** Wool retains its water: it soaks it up but never seeps. */
	@GameTest(maxTicks = 1400, padding = 24)
	public void woolRetainsWater(GameTestHelper h) {
		poolAndWall(h, Blocks.WOOL.white().defaultBlockState());
		conservation(h, 8, 6, 4);
		h.runAfterDelay(1200, () -> {
			long seeped = liquid(h, 5, 1, 1, 7, 5, 3);
			int wet = WaterCount.moisture(h, new BlockPos(4, 1, 2));
			h.assertTrue(seeped == 0, "Wool let " + seeped + " levels through");
			h.assertTrue(wet > 0, "Wool did not soak up any water");
			report(h, "woolRetainsWater", "wool moisture " + wet + " units, chamber dry");
			h.succeed();
		});
	}

	/** Obsidian (watertight rock) takes no water and lets none through. */
	@GameTest(maxTicks = 800, padding = 24)
	public void obsidianIsWatertight(GameTestHelper h) {
		poolAndWall(h, Blocks.OBSIDIAN.defaultBlockState());
		conservation(h, 8, 6, 4);
		h.runAfterDelay(600, () -> {
			h.assertTrue(liquid(h, 5, 1, 1, 7, 5, 3) == 0, "Water went through obsidian");
			h.assertTrue(WaterCount.moisture(h, new BlockPos(4, 1, 2)) == 0, "Obsidian holds moisture");
			report(h, "obsidianIsWatertight", "no moisture, chamber dry");
			h.succeed();
		});
	}

	/** Dirt under a pool gets wet and turns into mud (at the "mouille" stage). */
	@GameTest(maxTicks = 3000, padding = 24)
	public void dirtUnderPoolBecomesMud(GameTestHelper h) {
		box(h, 6, 6, 6);
		fill(h, 1, 1, 1, 5, 1, 5, Blocks.DIRT);
		fill(h, 1, 2, 1, 5, 4, 5, Blocks.WATER);
		conservation(h, 6, 6, 6);
		h.succeedWhen(() -> {
			int mud = 0;
			for (int x = 1; x <= 5; x++)
				for (int z = 1; z <= 5; z++)
					if (h.getBlockState(new BlockPos(x, 1, z)).is(Blocks.MUD)) mud++;
			h.assertTrue(mud >= 13, "Only " + mud + "/25 dirt blocks became mud");
			report(h, "dirtUnderPoolBecomesMud", mud + "/25 blocks of mud");
		});
	}

	/**
	 * Basalt is directional (along its axis 4, across 0). Two compartments, each a pool over one basalt block over
	 * a dry chamber: vertical basalt (axis Y) lets the water down; horizontal basalt (axis X) does not.
	 */
	@GameTest(maxTicks = 3000, padding = 24)
	public void basaltIsDirectional(GameTestHelper h) {
		// two columns of 1x1: x=1 (vertical basalt), x=3 (horizontal basalt), separated by obsidian
		fill(h, 0, 0, 0, 4, 6, 2, Blocks.OBSIDIAN);
		for (int x : new int[]{1, 3}) {
			fill(h, x, 1, 1, x, 5, 1, Blocks.AIR);           // chamber y=1..2, basalt y=3, pool y=4..5
			BlockState basalt = Blocks.BASALT.defaultBlockState()
					.setValue(RotatedPillarBlock.AXIS, x == 1 ? Direction.Axis.Y : Direction.Axis.X);
			h.setBlock(new BlockPos(x, 3, 1), basalt);
			fill(h, x, 4, 1, x, 5, 1, Blocks.WATER);
		}
		conservation(h, 4, 6, 2);
		h.succeedWhen(() -> {
			long vertical = liquid(h, 1, 1, 1, 1, 2, 1);
			long horizontal = liquid(h, 3, 1, 1, 3, 2, 1);
			int hMoisture = WaterCount.moisture(h, new BlockPos(3, 3, 1));
			h.assertTrue(horizontal == 0 && hMoisture == 0, "Horizontal basalt let water in (" + horizontal + " levels, "
					+ hMoisture + " units)");
			h.assertTrue(vertical > 0, "Vertical basalt let no water down yet");
			report(h, "basaltIsDirectional", "vertical: " + vertical + " levels below, horizontal: dry");
		});
	}
}
