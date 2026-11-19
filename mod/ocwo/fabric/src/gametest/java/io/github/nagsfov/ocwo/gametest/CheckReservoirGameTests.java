package io.github.nagsfov.ocwo.gametest;

import io.github.nagsfov.ocwo.fluids.WaterReserves;
import io.github.nagsfov.ocwo.fluids.WaterReservoirs;
import io.github.nagsfov.ocwo.fluids.WaterRest;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Abstract reservoirs (06-pression.md, "Réservoirs abstraits"): a large body of natural water at rest pays for what the
 * flow takes out of its own surface, without waking up; a small one flows for real. Alone in its batch (the zone
 * reserve is shared by everything in a zone).
 */
public class CheckReservoirGameTests {

	private static final int X1 = 29, Y1 = 5, Z1 = 14;

	private static void raw(GameTestHelper h, int x, int y, int z, BlockState s) {
		BlockPos p = h.absolutePos(new BlockPos(x, y, z));
		h.getLevel().getChunkAt(p).setBlockState(p, s, 0);
	}

	private static int level(GameTestHelper h, int x, int y, int z) {
		return WaterCount.liquidLevels(h, new BlockPos(x, y, z));
	}

	private static long visible(GameTestHelper h) {
		long sum = 0;
		for (int x = 0; x <= X1; x++)
			for (int y = 0; y <= Y1; y++)
				for (int z = 0; z <= Z1; z++) sum += level(h, x, y, z);
		return sum;
	}

	private static long reserves(GameTestHelper h, Set<Long> zones) {
		WaterReserves r = WaterReserves.get(h.getLevel());
		long sum = 0;
		for (long z : zones) sum += r.reserveOf(z) + r.cloudsOf(z);
		return sum;
	}

	/**
	 * Three natural bodies written like the generation, each behind an obsidian wall with an empty basin on the other
	 * side; the player opens the three walls:
	 * <ul>
	 *   <li>A: a lake of 12 x 12 x 3 (432 blocks), basin of 6 x 6 x 1: the basin fills, the lake pays out of its
	 *   surface, which goes down evenly (within one level) and stays at rest;</li>
	 *   <li>B: a pond of 2 x 2 x 1 (4 blocks, under the threshold of 8): it flows for real into its basin;</li>
	 *   <li>C: a pond of 2 x 2 x 2 (8 blocks, the threshold), basin of 2 blocks: it pays out of its surface, which goes
	 *   down evenly without being touched by the flow (once a block of it is empty, it is under the threshold and flows
	 *   like any small body: the basin is small enough for that not to happen here).</li>
	 * </ul>
	 * Every tick: visible water + reserves (units) constant.
	 */
	@GameTest(environment = "ocwo-gametest:reservoir", maxTicks = 900, padding = 32)
	public void largeBodiesPayFromTheirSurface(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		BlockState obs = Blocks.OBSIDIAN.defaultBlockState(), air = Blocks.AIR.defaultBlockState(), water = Blocks.WATER.defaultBlockState();
		for (int x = 0; x <= X1; x++)
			for (int z = 0; z <= Z1; z++)
				for (int y = 0; y <= Y1; y++) {
					boolean basinA = x >= 14 && x <= 19 && z >= 1 && z <= 6 && y == 1;
					boolean basinB = x >= 25 && x <= 27 && z >= 1 && z <= 3 && y == 1;
					boolean basinC = x >= 25 && x <= 26 && z == 8 && y == 1;
					level.setBlock(h.absolutePos(new BlockPos(x, y, z)), basinA || basinB || basinC ? air : obs, 2);
				}
		// the generated bodies (natural water at rest), open to the sky
		for (int x = 1; x <= 12; x++)
			for (int z = 1; z <= 12; z++)
				for (int y = 1; y <= Y1; y++) raw(h, x, y, z, y <= 3 ? water : air);
		for (int x = 22; x <= 23; x++) {
			for (int z = 1; z <= 2; z++)
				for (int y = 1; y <= Y1; y++) raw(h, x, y, z, y <= 1 ? water : air);
			for (int z = 8; z <= 9; z++)
				for (int y = 1; y <= Y1; y++) raw(h, x, y, z, y <= 2 ? water : air);
		}
		Set<Long> zones = new LinkedHashSet<>();
		for (int x : new int[]{0, X1})
			for (int z : new int[]{0, Z1}) zones.add(WaterReserves.zone(h.absolutePos(new BlockPos(x, 0, z))));

		long[] start = {-1};
		long[] payments0 = {0};
		h.onEachTick(() -> {
			if (start[0] < 0) return;
			long now = visible(h) * 16 + reserves(h, zones);
			if (now != start[0]) h.fail("water not conserved (units): " + start[0] + " -> " + now + " at tick " + h.getTick());
		});
		h.runAfterDelay(5, () -> {
			start[0] = visible(h) * 16 + reserves(h, zones);
			payments0[0] = WaterReservoirs.payments;
			// the player opens the three walls
			level.setBlock(h.absolutePos(new BlockPos(13, 1, 6)), air, Block.UPDATE_ALL);
			level.setBlock(h.absolutePos(new BlockPos(24, 1, 1)), air, Block.UPDATE_ALL);
			level.setBlock(h.absolutePos(new BlockPos(24, 1, 8)), air, Block.UPDATE_ALL);
		});
		h.runAfterDelay(800, () -> {
			// A: the basin filled, the lake surface went down evenly and the lake stayed at rest
			int basinA = 0;
			for (int x = 14; x <= 19; x++)
				for (int z = 1; z <= 6; z++)
					basinA += level(h, x, 1, z);
			int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE, awake = 0;
			for (int x = 1; x <= 12; x++)
				for (int z = 1; z <= 12; z++) {
					int top = level(h, x, 3, z);
					lo = Math.min(lo, top);
					hi = Math.max(hi, top);
					for (int y = 1; y <= 3; y++)
						if (!WaterRest.isResting(level, h.absolutePos(new BlockPos(x, y, z)))) awake++;
				}
			if (basinA < 250) h.fail("basin A holds only " + basinA + " levels");
			if (hi - lo > 1) h.fail("the lake surface did not go down evenly: top layer from " + lo + " to " + hi);
			if (hi >= 8) h.fail("the lake surface did not go down (top layer at " + hi + ")");
			if (awake > 0) h.fail(awake + " lake blocks are still awake");
			// B: under the threshold, the pond flowed for real (its blocks were changed by the flow)
			int pondB = 0;
			for (int x = 22; x <= 23; x++)
				for (int z = 1; z <= 2; z++) pondB += level(h, x, 1, z);
			if (pondB >= 32) h.fail("pond B (4 blocks) did not flow into its basin");
			if (WaterRest.isNatural(level, h.absolutePos(new BlockPos(22, 1, 2)))) h.fail("pond B's far block was never changed by the flow");
			// C: at the threshold, the pond paid out of its surface: its top layer went down evenly, quietly (natural, at
			// rest), its bottom layer is still full
			int cLo = Integer.MAX_VALUE, cHi = Integer.MIN_VALUE;
			StringBuilder sb = new StringBuilder();
			boolean quiet = true;
			for (int x = 22; x <= 23; x++)
				for (int z = 8; z <= 9; z++)
					for (int y = 1; y <= 2; y++) {
						BlockPos q = h.absolutePos(new BlockPos(x, y, z));
						int l = level(h, x, y, z);
						boolean mouth = x == 23 && z == 8 && y == 1;
						sb.append(" (").append(x).append(',').append(y).append(',').append(z).append(")=").append(l)
								.append(WaterRest.isNatural(level, q) ? "n" : "").append(WaterRest.isResting(level, q) ? "r" : "");
						if (y == 2) {
							cLo = Math.min(cLo, l);
							cHi = Math.max(cHi, l);
						}
						if (!mouth && (!WaterRest.isNatural(level, q) || (y == 1 && l != 8))) quiet = false;
					}
			if (cHi >= 8 || cHi - cLo > 1 || cLo <= 0 || !quiet) h.fail("pond C (8 blocks) did not pay quietly and evenly out of its surface:" + sb);
			long paid = WaterReservoirs.payments - payments0[0];
			if (paid == 0) h.fail("no reservoir payment");
			System.out.println("[OCWO-TEST] reservoirs: basin A " + basinA + " levels, lake top layer " + lo + ".." + hi + ", pond B "
					+ pondB + " levels, pond C top layer " + cLo + ".." + cHi + ", " + paid + " payments, " + WaterReservoirs.searches
					+ " searches so far");
			h.succeed();
		});
	}
}
