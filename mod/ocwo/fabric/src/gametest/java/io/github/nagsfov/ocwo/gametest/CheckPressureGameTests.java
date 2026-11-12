package io.github.nagsfov.ocwo.gametest;

import io.github.nagsfov.ocwo.fluids.FluidUtils;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * OCWO pressure scenarios (see 06-pression.md). Every scenario is built by code, checks its expected final
 * state, and checks at every tick that the total amount of water in the area never changes (conservation).
 */
public class PressureGameTests {

	private static final int MAX = FluidUtils.MAX_LEVEL;

	// ------------------------------------------------------------------ helpers

	private static void fill(GameTestHelper h, int x0, int y0, int z0, int x1, int y1, int z1, Block block) {
		for (int x = x0; x <= x1; x++)
			for (int y = y0; y <= y1; y++)
				for (int z = z0; z <= z1; z++)
					h.setBlock(x, y, z, block);
	}

	private static int level(GameTestHelper h, int x, int y, int z) {
		FluidState fs = h.getLevel().getFluidState(h.absolutePos(new BlockPos(x, y, z)));
		return fs.getType().isSame(Fluids.WATER) ? fs.getAmount() : 0;
	}

	/** Total levels of water in the box. */
	private static int total(GameTestHelper h, int x0, int y0, int z0, int x1, int y1, int z1) {
		int sum = 0;
		for (int x = x0; x <= x1; x++)
			for (int y = y0; y <= y1; y++)
				for (int z = z0; z <= z1; z++)
					sum += level(h, x, y, z);
		return sum;
	}

	/** Fails the test as soon as the amount of water in the box changes. */
	private static void checkConservation(GameTestHelper h, int x0, int y0, int z0, int x1, int y1, int z1) {
		int initial = total(h, x0, y0, z0, x1, y1, z1);
		System.out.println("[OCWO-TEST] water at start: " + initial + " levels");
		h.onEachTick(() -> {
			int now = total(h, x0, y0, z0, x1, y1, z1);
			if (now != initial) {
				h.fail("Water not conserved: " + initial + " levels at start, " + now + " now");
			}
		});
	}

	/** Height (y * 8 + level) of the highest water in a column, or -1. */
	private static int columnHeight(GameTestHelper h, int x, int z, int y0, int y1) {
		for (int y = y1; y >= y0; y--) {
			int l = level(h, x, y, z);
			if (l > 0) return y * MAX + l;
		}
		return -1;
	}

	// ------------------------------------------------------------------ U-tube

	/**
	 * Two shafts (x=1 and x=5) joined at the bottom by a junction 1 wide and 2 high (x=2..4, y=1..2).
	 * 12 blocks of water poured in the left shaft must end with both surfaces at the same height (within one
	 * level) and the junction full.
	 */
	@GameTest(maxTicks = 1200, padding = 24)
	public void uTubeNarrowJunction(GameTestHelper h) {
		fill(h, 0, 0, 0, 6, 14, 2, Blocks.STONE);
		fill(h, 1, 1, 1, 1, 14, 1, Blocks.AIR);
		fill(h, 5, 1, 1, 5, 14, 1, Blocks.AIR);
		fill(h, 2, 1, 1, 4, 2, 1, Blocks.AIR);
		fill(h, 1, 1, 1, 1, 12, 1, Blocks.WATER);
		checkConservation(h, 0, 0, 0, 6, 15, 2);
		h.succeedWhen(() -> {
			for (int x = 1; x <= 5; x++)
				for (int y = 1; y <= 2; y++)
					h.assertTrue(level(h, x, y, 1) == MAX, "Bottom of the U not full at " + x + "," + y);
			int left = columnHeight(h, 1, 1, 1, 14);
			int right = columnHeight(h, 5, 1, 1, 14);
			h.assertTrue(Math.abs(left - right) <= 1, "Surfaces not balanced: left " + left + ", right " + right);
			report(h, "uTubeNarrowJunction", "left " + left + ", right " + right);
		});
	}

	// ------------------------------------------------------------------ corridor under a lake

	/**
	 * A 7x7x8 lake and, behind a wall, a corridor 1 wide, 10 long, 3 high (x=9..18, y=1..3, z=4), opened by one
	 * missing wall block at height {@code openingY}. The lake surface stays above the corridor, so the whole
	 * corridor must end full.
	 */
	private static void corridor(GameTestHelper h, int openingY, boolean step) {
		fill(h, 0, 0, 0, 19, 10, 8, Blocks.STONE);
		fill(h, 1, 1, 1, 7, 10, 7, Blocks.AIR);
		fill(h, 9, 1, 4, 18, 3, 4, Blocks.AIR);
		h.setBlock(8, openingY, 4, Blocks.AIR);
		if (step) h.setBlock(9, 1, 4, Blocks.STONE);
		fill(h, 1, 1, 1, 7, 8, 7, Blocks.WATER);
		checkConservation(h, 0, 0, 0, 19, 11, 8);
		h.succeedWhen(() -> {
			for (int x = 9; x <= 18; x++)
				for (int y = 1; y <= 3; y++) {
					if (step && x == 9 && y == 1) continue;
					h.assertTrue(level(h, x, y, 4) == MAX, "Corridor not full at " + x + "," + y + " (" + level(h, x, y, 4) + "/8)");
				}
			report(h, "corridor (opening y=" + openingY + (step ? ", step" : "") + ")", "corridor full");
		});
	}

	@GameTest(maxTicks = 1200, padding = 24)
	public void corridorOpeningAtHeight1(GameTestHelper h) {
		corridor(h, 1, false);
	}

	@GameTest(maxTicks = 1200, padding = 24)
	public void corridorOpeningAtHeight2(GameTestHelper h) {
		corridor(h, 2, false);
	}

	@GameTest(maxTicks = 1200, padding = 24)
	public void corridorWithStepBehindOpening(GameTestHelper h) {
		corridor(h, 2, true);
	}

	// ------------------------------------------------------------------ block placed deep in a lake

	/**
	 * Glass placed (with fluid displacement, like a player) at the bottom of a full 7x7x8 lake: the displaced
	 * water must go up to the surface, not stay inside the glass.
	 */
	@GameTest(maxTicks = 400, padding = 24)
	public void glassAtLakeBottom(GameTestHelper h) {
		fill(h, 0, 0, 0, 8, 10, 8, Blocks.STONE);
		fill(h, 1, 1, 1, 7, 10, 7, Blocks.AIR);
		fill(h, 1, 1, 1, 7, 8, 7, Blocks.WATER);
		checkConservation(h, 0, 0, 0, 8, 11, 8);
		BlockPos glass = new BlockPos(4, 1, 4);
		h.runAfterDelay(20, () -> h.getLevel().setBlock(h.absolutePos(glass), Blocks.GLASS.defaultBlockState(),
				Block.UPDATE_ALL | FluidUtils.DISPLACE_FLAG));
		h.runAfterDelay(100, () -> {
			h.assertTrue(h.getBlockState(glass).is(Blocks.GLASS), "Glass was not placed");
			h.assertTrue(level(h, glass.getX(), glass.getY(), glass.getZ()) == 0,
					"Water stayed inside the glass (" + level(h, glass.getX(), glass.getY(), glass.getZ()) + "/8)");
			report(h, "glassAtLakeBottom", "glass dry");
			h.succeed();
		});
	}

	private static void report(GameTestHelper h, String test, String detail) {
		System.out.println("[OCWO-TEST] " + test + " OK at tick " + h.getTick() + " (" + detail + ")");
	}
}
