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

	/** A gravel wall soaks up the pool and seeps into the dry chamber behind it. */
	@GameTest(environment = "ocwo-gametest:moisture_fast", maxTicks = 3000, padding = 24)
	public void gravelWallSeeps(GameTestHelper h) {
		poolAndWall(h, Blocks.GRAVEL.defaultBlockState());
		conservation(h, 8, 6, 4);
		h.succeedWhen(() -> {
			long seeped = liquid(h, 5, 1, 1, 7, 5, 3);
			h.assertTrue(seeped > 0, "No water seeped through the gravel wall yet");
			report(h, "gravelWallSeeps", seeped + " levels seeped, wall moisture at (4,1,2) = "
					+ WaterCount.moisture(h, new BlockPos(4, 1, 2)) + " units");
		});
	}

	/** Wool retains its water: it soaks it up but never seeps. */
	@GameTest(environment = "ocwo-gametest:moisture_fast", maxTicks = 1400, padding = 24)
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
	@GameTest(environment = "ocwo-gametest:moisture_fast", maxTicks = 800, padding = 24)
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
	@GameTest(environment = "ocwo-gametest:moisture_fast", maxTicks = 3000, padding = 24)
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

	/** Stone holds no water any more (reduced moisture materials, 18/11/2026): a cobblestone wall stays dry. */
	@GameTest(environment = "ocwo-gametest:moisture_fast", maxTicks = 800, padding = 24)
	public void cobblestoneHoldsNoWater(GameTestHelper h) {
		poolAndWall(h, Blocks.COBBLESTONE.defaultBlockState());
		conservation(h, 8, 6, 4);
		h.runAfterDelay(600, () -> {
			h.assertTrue(liquid(h, 5, 1, 1, 7, 5, 3) == 0, "Water went through cobblestone");
			h.assertTrue(WaterCount.moisture(h, new BlockPos(4, 1, 2)) == 0, "Cobblestone holds moisture");
			report(h, "cobblestoneHoldsNoWater", "no moisture, chamber dry");
			h.succeed();
		});
	}

	/** Sand under the sea soaks up water, but never from the cells of seagrass and kelp (that would destroy them). */
	@GameTest(maxTicks = 1400, padding = 24)
	public void seagrassOnSandSurvives(GameTestHelper h) {
		box(h, 6, 6, 6);
		fill(h, 1, 1, 1, 5, 1, 5, Blocks.SAND);
		fill(h, 1, 2, 1, 5, 4, 5, Blocks.WATER);
		for (int x = 1; x <= 5; x += 2)
			for (int z = 1; z <= 5; z += 2)
				h.setBlock(new BlockPos(x, 2, z), Blocks.SEAGRASS);
		h.setBlock(new BlockPos(2, 2, 2), Blocks.KELP);
		conservation(h, 6, 6, 6);
		h.runAfterDelay(1200, () -> {
			int alive = 0;
			for (int x = 1; x <= 5; x += 2)
				for (int z = 1; z <= 5; z += 2)
					if (h.getBlockState(new BlockPos(x, 2, z)).is(Blocks.SEAGRASS)) alive++;
			boolean kelp = h.getBlockState(new BlockPos(2, 2, 2)).is(Blocks.KELP);
			long sand = 0;
			for (int x = 1; x <= 5; x++)
				for (int z = 1; z <= 5; z++) sand += WaterCount.moisture(h, new BlockPos(x, 1, z));
			h.assertTrue(alive == 9 && kelp, "Aquatic plants destroyed: " + alive + "/9 seagrass, kelp " + kelp);
			h.assertTrue(sand > 0, "The sand soaked up nothing");
			report(h, "seagrassOnSandSurvives", "9/9 seagrass and kelp alive, sand moisture " + sand + " units");
			h.succeed();
		});
	}

	/**
	 * Mud made by the simulation, mined then placed back: liquid water + moisture + the water carried by the item
	 * (min(moisture, fixed water) when mined) stays constant, and the placed mud does not turn back into dirt.
	 */
	@GameTest(environment = "ocwo-gametest:moisture_fast", maxTicks = 1000, padding = 24)
	public void mudMinedAndPlacedKeepsWater(GameTestHelper h) {
		box(h, 4, 4, 4);
		BlockPos p = new BlockPos(2, 1, 2);
		h.setBlock(p, Blocks.DIRT);
		var sim = io.github.nagsfov.ocwo.fluids.CustomFluidTicks.get(h.getLevel()).moisture();
		// 4 levels of moisture in the dirt: "mouille" (4/6) -> becomes mud at its next step
		sim.setUnits(h.absolutePos(p), 64);
		sim.activate(h.absolutePos(p));
		long[] before = {-1};
		long[] carried = {0};
		h.succeedWhen(() -> {
			if (before[0] < 0) {
				h.assertTrue(h.getBlockState(p).is(Blocks.MUD), "Dirt did not become mud yet");
				before[0] = WaterCount.units(h, 0, 0, 0, 4, 4, 4);
				int moisture = WaterCount.moisture(h, p);
				// mine it: the item carries min(moisture, fixed water = 2 levels)
				carried[0] = Math.min(moisture, 2 * io.github.nagsfov.ocwo.moisture.Moisture.UNITS_PER_LEVEL);
				h.getLevel().setBlock(h.absolutePos(p), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				long afterMining = WaterCount.units(h, 0, 0, 0, 4, 4, 4);
				h.assertTrue(afterMining == before[0] - carried[0], "Mining: " + before[0] + " - " + carried[0] + " expected, got " + afterMining);
				// place it back
				h.getLevel().setBlock(h.absolutePos(p), Blocks.MUD.defaultBlockState(), Block.UPDATE_ALL);
				h.fail("waiting");
				return;
			}
			long now = WaterCount.units(h, 0, 0, 0, 4, 4, 4);
			h.assertTrue(now == before[0] - carried[0] + 2L * io.github.nagsfov.ocwo.moisture.Moisture.UNITS_PER_LEVEL,
					"Total after placing back: " + now + " (before mining " + before[0] + ", carried " + carried[0] + ")");
			h.assertTrue(h.getTick() > 200, "waiting for the placed mud to be simulated");
			h.assertTrue(h.getBlockState(p).is(Blocks.MUD), "The placed mud turned back into dirt");
			report(h, "mudMinedAndPlacedKeepsWater", "carried " + carried[0] + " units, total " + now);
		});
	}
}
