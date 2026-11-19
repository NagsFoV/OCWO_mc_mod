package io.github.nagsfov.ocwo.gametest;

import io.github.nagsfov.ocwo.fluids.WaterReserves;
import io.github.nagsfov.ocwo.moisture.MoistureSimulation;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Zone sources (03-humidite-et-etats-des-blocs.md, "Sources de zone"): the water of a saturated cluster comes out at
 * its best outlet, the lowest free cell below or beside it, found once (bounded, deterministic search), recorded by the
 * zone and reused while it stays free and touches the wet blocks. Alone in its batch.
 */
public class CheckZoneSourceGameTests {

	private static final int X1 = 8, Y1 = 7, Z1 = 4;

	/**
	 * A pool (x 1..3, 6 blocks deep) behind a gravel wall (x 4) and an empty chamber (x 5..7): the wall saturates and
	 * the water comes out at the bottom of the chamber, next to the wall, not at the height where it soaks in; the
	 * outlets are found far less often than water comes out (the zone keeps them); water + evaporation constant.
	 */
	@GameTest(environment = "ocwo-gametest:zone_source", maxTicks = 700, padding = 32)
	public void saturatedWallSpringsAtItsBottom(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		BlockState obs = Blocks.OBSIDIAN.defaultBlockState();
		for (int x = 0; x <= X1; x++)
			for (int z = 0; z <= Z1; z++)
				for (int y = 0; y <= Y1; y++) {
					boolean inside = x >= 1 && x <= X1 - 1 && z >= 1 && z <= Z1 - 1 && y >= 1 && y <= Y1 - 1;
					BlockState s = obs;
					if (inside && x <= 3) s = Blocks.WATER.defaultBlockState();
					else if (inside && x == 4) s = Blocks.GRAVEL.defaultBlockState();
					else if (inside) s = Blocks.AIR.defaultBlockState();
					level.setBlock(h.absolutePos(new BlockPos(x, y, z)), s, 2);
				}
		long zone = WaterReserves.zone(h.absolutePos(new BlockPos(4, 1, 2)));
		long[] start = {-1}, searches0 = {0}, seeps0 = {0};
		boolean[] first = {false};
		h.onEachTick(() -> {
			if (start[0] < 0) return;
			long now = WaterCount.units(h, 0, 0, 0, X1, Y1, Z1);
			if (now != start[0]) h.fail("water not conserved (units): " + start[0] + " -> " + now + " at tick " + h.getTick());
			if (first[0]) return;
			int low = 0, high = 0;
			for (int x = 5; x <= 7; x++)
				for (int z = 1; z <= 3; z++)
					for (int y = 1; y <= Y1 - 1; y++) {
						int l = WaterCount.liquidLevels(h, new BlockPos(x, y, z));
						if (y == 1) low += l;
						else high += l;
					}
			if (low + high == 0) return;
			// the first water in the chamber: at its bottom, out of an outlet the zone recorded next to the wall
			first[0] = true;
			if (high > 0) h.fail("the first water came out above the bottom of the chamber (" + high + " levels above, " + low + " at the bottom)");
			boolean recorded = false;
			for (long p : WaterReserves.get(level).sourcesOf(zone)) {
				BlockPos rel = BlockPos.of(p).subtract(h.absolutePos(BlockPos.ZERO));
				if (rel.getX() == 5 && rel.getY() == 1) recorded = true;
			}
			if (!recorded) h.fail("no source recorded at the bottom of the wall: " + WaterReserves.get(level).sourcesOf(zone));
		});
		h.runAfterDelay(1, () -> {
			start[0] = WaterCount.units(h, 0, 0, 0, X1, Y1, Z1);
			searches0[0] = MoistureSimulation.sourceSearches;
			seeps0[0] = MoistureSimulation.sourceSeeps;
		});
		h.runAfterDelay(600, () -> {
			long searches = MoistureSimulation.sourceSearches - searches0[0], seeps = MoistureSimulation.sourceSeeps - seeps0[0];
			long chamber = 0;
			for (int x = 5; x <= 7; x++)
				for (int z = 1; z <= 3; z++)
					for (int y = 1; y <= Y1 - 1; y++) chamber += WaterCount.liquidLevels(h, new BlockPos(x, y, z));
			System.out.println("[OCWO-TEST] zone sources: " + seeps + " levels came out of outlets, " + searches + " outlet searches, chamber "
					+ chamber + " levels, sources recorded now " + WaterReserves.get(level).sourcesOf(zone).size());
			if (!first[0]) h.fail("no water came out of the wall");
			if (seeps < 20) h.fail("too little water came out to judge: " + seeps + " levels");
			if (searches * 3 > seeps) h.fail("the outlets were searched again too often: " + searches + " searches for " + seeps + " levels");
			h.succeed();
		});
	}
}
