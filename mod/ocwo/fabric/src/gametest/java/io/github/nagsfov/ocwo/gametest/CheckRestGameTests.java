package io.github.nagsfov.ocwo.gametest;

import io.github.nagsfov.ocwo.fluids.CustomFluidTicks;
import io.github.nagsfov.ocwo.fluids.WaterRest;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Water at rest (06-pression.md): water written like the world generation does (straight into the chunk, without a
 * block change) is not simulated; a block changed next to it wakes it, and only the water that moves wakes up.
 */
public class CheckRestGameTests {

	/** Like world generation: written into the chunk, no block change event. */
	private static void raw(GameTestHelper h, int x, int y, int z, BlockState s) {
		BlockPos p = h.absolutePos(new BlockPos(x, y, z));
		h.getLevel().getChunkAt(p).setBlockState(p, s, 0);
	}

	private static int level(GameTestHelper h, int x, int y, int z) {
		FluidState fs = h.getLevel().getFluidState(h.absolutePos(new BlockPos(x, y, z)));
		return fs.getType().isSame(Fluids.WATER) ? fs.getAmount() : 0;
	}

	private static long total(GameTestHelper h) {
		long sum = 0;
		for (int x = 0; x <= 20; x++)
			for (int y = 0; y <= 4; y++)
				for (int z = 0; z <= 8; z++) sum += level(h, x, y, z);
		return sum;
	}

	/**
	 * Two pools in obsidian, each with an empty cell inside (a "hole" the generation left): pool A from x 1..5,
	 * pool B from x 12..16, rows z 1..5, water at y 1..2, hole at the corner.
	 */
	private static void build(GameTestHelper h) {
		BlockState obs = Blocks.OBSIDIAN.defaultBlockState(), water = Blocks.WATER.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
		for (int x = 0; x <= 20; x++)
			for (int z = 0; z <= 8; z++)
				for (int y = 0; y <= 4; y++) {
					boolean a = x >= 1 && x <= 5, b = x >= 12 && x <= 16;
					boolean inside = (a || b) && z >= 1 && z <= 5 && y >= 1;
					boolean pocket = x >= 2 && x <= 4 && z == 7 && y >= 1 && y <= 2; // sealed empty pocket behind pool A
					if (pocket) {
						h.getLevel().setBlock(h.absolutePos(new BlockPos(x, y, z)), air, 2);
						continue;
					}
					BlockState s = !inside ? obs : y <= 2 ? water : air;
					if (inside && y <= 2 && (x == 5 || x == 16) && z == 5) s = air; // the hole
					if (!inside) h.getLevel().setBlock(h.absolutePos(new BlockPos(x, y, z)), s, 2); // the basin, normally
				}
		// then the water, written like the generation does
		for (int x = 0; x <= 20; x++)
			for (int z = 0; z <= 6; z++)
				for (int y = 1; y <= 4; y++) {
					boolean a = x >= 1 && x <= 5, b = x >= 12 && x <= 16;
					if (!((a || b) && z >= 1 && z <= 5)) continue;
					BlockState s = y <= 2 ? water : air;
					if (y <= 2 && (x == 5 || x == 16) && z == 5) s = air;
					raw(h, x, y, z, s);
				}
	}

	@GameTest(maxTicks = 300, padding = 32)
	public void generatedWaterStaysAtRest(GameTestHelper h) {
		build(h);
		ServerLevel level = h.getLevel();
		// wake-up calls that do not change a block: scheduled fluid ticks and pressure triggers on the water
		for (int x = 1; x <= 4; x++)
			for (int z = 1; z <= 4; z++) {
				BlockPos p = h.absolutePos(new BlockPos(x, 1, z));
				level.scheduleTick(p, Fluids.WATER, 1);
				CustomFluidTicks.get(level).enqueuePressure(p, Fluids.WATER);
			}
		long start = total(h);
		h.runAfterDelay(200, () -> {
			if (!WaterRest.isResting(level, h.absolutePos(new BlockPos(2, 1, 2)))) h.fail("generated water is not at rest");
			if (level(h, 5, 1, 5) != 0 || level(h, 5, 2, 5) != 0) h.fail("water at rest flowed into the hole");
			if (total(h) != start) h.fail("water changed: " + start + " -> " + total(h));
			int work = CustomFluidTicks.get(level).bodies().workIn(h.absolutePos(BlockPos.ZERO), h.absolutePos(new BlockPos(20, 4, 6)));
			if (work != 0) h.fail("pressure works on water at rest (" + work + " bodies)");
			h.succeed();
		});
	}

	/** Same disturbance with the rule off (everything simulated): reference for the conservation. */
	@GameTest(environment = "ocwo-gametest:rest_off", maxTicks = 600, padding = 32)
	public void disturbanceWithoutRest(GameTestHelper h) {
		build(h);
		long start = total(h);
		h.runAfterDelay(5, () -> h.getLevel().setBlock(h.absolutePos(new BlockPos(3, 1, 6)), Blocks.AIR.defaultBlockState(), 3));
		h.runAfterDelay(300, () -> {
			System.out.println("[OCWO-TEST] rest off: water " + start + " -> " + total(h));
			if (total(h) != start) h.fail("water not conserved without rest: " + start + " -> " + total(h));
			h.succeed();
		});
	}

	@GameTest(maxTicks = 600, padding = 32)
	public void disturbanceWakesLocally(GameTestHelper h) {
		build(h);
		ServerLevel level = h.getLevel();
		long start = total(h);
		h.runAfterDelay(5, () -> {
			// the player opens the wall between pool A and the sealed pocket
			level.setBlock(h.absolutePos(new BlockPos(3, 1, 6)), Blocks.AIR.defaultBlockState(), 3);
		});
		long[] last = {start};
		h.onEachTick(() -> {
			long now = total(h);
			if (now != last[0]) {
				StringBuilder sb = new StringBuilder();
				for (int x = 0; x <= 6; x++) { sb.append(" x").append(x).append(':'); for (int z = 0; z <= 6; z++) sb.append(level(h, x, 1, z)).append(level(h, x, 2, z) > 0 ? "+" + level(h, x, 2, z) : "").append(','); }
				System.out.println("[OCWO-TEST] rest total " + last[0] + " -> " + now + " at " + h.getTick() + sb);
				last[0] = now;
			}
		});
		h.runAfterDelay(300, () -> {
			if (total(h) != start) h.fail("water not conserved: " + start + " -> " + total(h));
			// pool A woke up and filled its hole and the new pocket
			if (level(h, 3, 1, 7) == 0) h.fail("pool A did not wake up (pocket still empty)");
			// pool B, never touched, is still at rest with its hole empty
			if (!WaterRest.isResting(level, h.absolutePos(new BlockPos(13, 1, 2)))) h.fail("pool B woke up");
			if (level(h, 16, 1, 5) != 0) h.fail("pool B flowed");
			int awake = WaterRest.awakeAround(level, h.absolutePos(new BlockPos(3, 1, 3)), 2);
			System.out.println("[OCWO-TEST] rest: " + awake + " awake water blocks after the disturbance (pool A has 48)");
			h.succeed();
		});
	}
}
