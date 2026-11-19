package io.github.nagsfov.ocwo.gametest;

import io.github.nagsfov.ocwo.fluids.CustomFluidTicks;
import io.github.nagsfov.ocwo.moisture.Moisture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Water accounting for the game tests: liquid water AND block moisture, in moisture units
 * (1 water level = {@link Moisture#UNITS_PER_LEVEL} units). This is what must stay constant.
 */
final class WaterCount {

	private WaterCount() {
	}

	static int liquidLevels(GameTestHelper h, BlockPos rel) {
		FluidState fs = h.getLevel().getFluidState(h.absolutePos(rel));
		return fs.getType().isSame(Fluids.WATER) ? fs.getAmount() : 0;
	}

	static int moisture(GameTestHelper h, BlockPos rel) {
		return CustomFluidTicks.get(h.getLevel()).moisture().units(h.absolutePos(rel));
	}

	/** Margin added around every measured box: moisture can soak into the blocks just outside the build. */
	static final int MARGIN = 3;

	/** Liquid water + moisture of the box (plus {@link #MARGIN}), in units. */
	static long units(GameTestHelper h, int x0, int y0, int z0, int x1, int y1, int z1) {
		x0 -= MARGIN; y0 -= MARGIN; z0 -= MARGIN; x1 += MARGIN; y1 += MARGIN; z1 += MARGIN;
		long sum = 0;
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int x = x0; x <= x1; x++)
			for (int y = y0; y <= y1; y++)
				for (int z = z0; z <= z1; z++) {
					p.set(x, y, z);
					sum += (long) liquidLevels(h, p) * Moisture.UNITS_PER_LEVEL + moisture(h, p);
				}
		return sum;
	}

	/**
	 * Reserves + clouds of the zones (4 x 4 chunks) touched by the box (plus {@link #MARGIN}), in units: where a block
	 * that goes away puts its generation moisture. Shared by every test in the same zone: only meaningful when the
	 * other tests of the batch do not feed the ledger at the same time.
	 */
	static long ledger(GameTestHelper h, int x0, int z0, int x1, int z1) {
		var r = io.github.nagsfov.ocwo.fluids.WaterReserves.get(h.getLevel());
		java.util.Set<Long> zones = new java.util.HashSet<>();
		long sum = 0;
		for (int x : new int[]{x0 - MARGIN, x1 + MARGIN})
			for (int z : new int[]{z0 - MARGIN, z1 + MARGIN}) {
				long zone = io.github.nagsfov.ocwo.fluids.WaterReserves.zone(h.absolutePos(new BlockPos(x, 0, z)));
				if (zones.add(zone)) sum += r.reserveOf(zone) + r.cloudsOf(zone);
			}
		return sum;
	}

	/** Per-cell snapshot "x,y,z" -> "liquid/moisture" (diagnostics). */
	static java.util.Map<String, String> snapshot(GameTestHelper h, int x0, int y0, int z0, int x1, int y1, int z1) {
		java.util.Map<String, String> m = new java.util.HashMap<>();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
		for (int x = x0 - MARGIN; x <= x1 + MARGIN; x++)
			for (int y = y0 - MARGIN; y <= y1 + MARGIN; y++)
				for (int z = z0 - MARGIN; z <= z1 + MARGIN; z++) {
					p.set(x, y, z);
					int l = liquidLevels(h, p), mo = moisture(h, p);
					if (l != 0 || mo != 0) m.put(x + "," + y + "," + z, l + "/" + mo + " " + h.getBlockState(p).getBlock());
				}
		return m;
	}

	static void printDiff(java.util.Map<String, String> before, java.util.Map<String, String> after) {
		java.util.Set<String> keys = new java.util.TreeSet<>(before.keySet());
		keys.addAll(after.keySet());
		for (String k : keys) {
			String a = before.get(k), b = after.get(k);
			if (!java.util.Objects.equals(a, b)) System.out.println("[OCWO-DIFF] " + k + ": " + a + " -> " + b);
		}
	}
}
