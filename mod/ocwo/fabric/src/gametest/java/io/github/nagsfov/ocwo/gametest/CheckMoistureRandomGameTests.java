package io.github.nagsfov.ocwo.gametest;

import io.github.nagsfov.ocwo.fluids.CustomFluidTicks;
import io.github.nagsfov.ocwo.fluids.WaterRest;
import io.github.nagsfov.ocwo.moisture.MoistureSimulation;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Moisture by random ticks and generation moisture (03-humidite-et-etats-des-blocs.md): sand lets water through
 * faster than dirt on average; soil written like the world generation is at its equilibrium, stores nothing and
 * does nothing; water is conserved (liquid + moisture).
 */
public class CheckMoistureRandomGameTests {

	/** A pool over a floor of sand (x 1..4) and dirt (x 6..9): sand soaks up faster (moderate random tick rate). */
	@GameTest(environment = "ocwo-gametest:moisture_rate", maxTicks = 500, padding = 24)
	public void sandSoaksFasterThanDirt(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		BlockState obs = Blocks.OBSIDIAN.defaultBlockState();
		for (int x = 0; x <= 10; x++)
			for (int z = 0; z <= 5; z++)
				for (int y = 0; y <= 4; y++) {
					boolean inside = x >= 1 && x <= 9 && z >= 1 && z <= 4;
					BlockState s = obs;
					if (inside && y == 1) s = x <= 4 ? Blocks.SAND.defaultBlockState() : x == 5 ? obs : Blocks.DIRT.defaultBlockState();
					else if (inside && (y == 2 || y == 3)) s = Blocks.WATER.defaultBlockState();
					else if (inside && y == 4) s = Blocks.AIR.defaultBlockState();
					level.setBlock(h.absolutePos(new BlockPos(x, y, z)), s, 2);
				}
		long start = WaterCount.units(h, 0, 0, 0, 10, 4, 5);
		h.runAfterDelay(300, () -> {
			MoistureSimulation sim = CustomFluidTicks.get(level).moisture();
			double sand = 0, dirt = 0;
			for (int x = 1; x <= 9; x++)
				for (int z = 1; z <= 4; z++) {
					if (x == 5) continue;
					BlockPos p = h.absolutePos(new BlockPos(x, 1, z));
					double ratio = sim.units(p) / (x <= 4 ? 48.0 : 96.0); // capacities: sand 3 levels, dirt 6 levels (16 units each)
					if (x <= 4) sand += ratio / 16;
					else dirt += ratio / 16;
				}
			long now = WaterCount.units(h, 0, 0, 0, 10, 4, 5);
			System.out.println(String.format(java.util.Locale.ROOT, "[OCWO-TEST] random ticks: sand %.0f %% full, dirt %.0f %% full (water %d -> %d units)",
					sand * 100, dirt * 100, start, now));
			if (now != start) h.fail("water not conserved: " + start + " -> " + now);
			if (sand <= dirt) h.fail(String.format("sand (%.2f) should soak up faster than dirt (%.2f)", sand, dirt));
			if (sand == 0) h.fail("nothing soaked up");
			h.succeed();
		});
	}

	/** Soil written like the world generation: at its equilibrium, nothing stored, nothing simulated, no water woken. */
	@GameTest(maxTicks = 400, padding = 24)
	public void generatedSoilAtEquilibrium(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		BlockState dirt = Blocks.DIRT.defaultBlockState(), stone = Blocks.STONE.defaultBlockState(), water = Blocks.WATER.defaultBlockState();
		for (int x = 0; x <= 4; x++)
			for (int z = 0; z <= 4; z++)
				for (int y = 0; y <= 3; y++) {
					BlockState s = y == 0 ? stone : (x == 2 && z == 2 && y <= 2) ? water : y <= 2 ? dirt : Blocks.AIR.defaultBlockState();
					BlockPos p = h.absolutePos(new BlockPos(x, y, z));
					level.getChunkAt(p).setBlockState(p, s, 0); // like the generation: no block change event
				}
		MoistureSimulation sim = CustomFluidTicks.get(level).moisture();
		BlockPos d = h.absolutePos(new BlockPos(1, 1, 2)), w = h.absolutePos(new BlockPos(2, 1, 2));
		int eq = sim.equilibrium(d, level.getBlockState(d));
		h.runAfterDelay(300, () -> {
			System.out.println("[OCWO-TEST] generation moisture of the dirt here: " + eq + " units (sea level " + level.getSeaLevel() + ")");
			if (sim.units(d) != eq) h.fail("dirt moved away from its equilibrium: " + sim.units(d) + " / " + eq);
			if (!level.getBlockState(d).is(Blocks.DIRT)) h.fail("generation moisture changed the block: " + level.getBlockState(d));
			if (sim.isActive(d)) h.fail("dirt at its equilibrium is active");
			if (!WaterRest.isResting(level, w)) h.fail("the water next to it woke up");
			if (level.getFluidState(w).getAmount() != 8) h.fail("the water was absorbed");
			h.succeed();
		});
	}
}
