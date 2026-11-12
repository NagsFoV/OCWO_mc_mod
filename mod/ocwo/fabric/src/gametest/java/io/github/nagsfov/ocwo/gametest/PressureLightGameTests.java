package io.github.nagsfov.ocwo.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/**
 * Sky light after draining a lake that has fences under water (water kept in the fluid layer of the fences).
 * Part of the regular run (gradlew :fabric:runGameTest). See 05-problemes-connus.md, section 1.
 */
public class PressureLightGameTests {

	@GameTest(maxTicks = 6_000, padding = 32)
	public void drainLakeWithFencesLight(GameTestHelper h) {
		DrainScenario.Pool p = new DrainScenario.Pool(16, 9, 8, 100, 8);
		DrainScenario.Run run = new DrainScenario.Run(h, p, "drainLakeWithFencesLight", 5_000, false);
		DrainScenario.forceChunks(h, p.size(), true);
		DrainScenario.build(h, p);
		// Fences under water: the water around them flows into their fluid layer.
		for (int x = 2; x <= 15; x++)
			for (int z = 4; z <= 12; z += 4)
				h.setBlock(new BlockPos(x, p.lakeMinY() + 3, z), Blocks.OAK_FENCE.defaultBlockState());
		h.runAfterDelay(5, () -> {
			run.initial = DrainScenario.total(h, p, 1, p.lakeMaxY() + 1);
			System.out.println("[OCWO-TEST] drainLakeWithFencesLight: " + run.initial + " levels of water");
			run.startTick = h.getTick();
			DrainScenario.openHoles(h, p);
		});
		h.onEachTick(run::tick);
	}
}
