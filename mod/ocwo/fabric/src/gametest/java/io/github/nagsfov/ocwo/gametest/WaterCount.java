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

	static {
		// what evaporates is counted where it left the ground (the clouds of a zone are shared by the tests in it)
		io.github.nagsfov.ocwo.fluids.WaterReserves.trackClouds = true;
	}

	/**
	 * Liquid water + moisture of the box (plus {@link #MARGIN}) + what its blocks sent up to the clouds since the
	 * tests started, in units. This is what must stay constant for a closed box.
	 */
	static long units(GameTestHelper h, int x0, int y0, int z0, int x1, int y1, int z1) {
		return blockUnits(h, x0, y0, z0, x1, y1, z1) + sentUp(h, x0, y0, z0, x1, y1, z1) + itemUnits(h, x0, y0, z0, x1, y1, z1);
	}

	/** Water carried by the sponge items lying in the box (plus {@link #MARGIN}), in units. */
	static long itemUnits(GameTestHelper h, int x0, int y0, int z0, int x1, int y1, int z1) {
		BlockPos a = h.absolutePos(new BlockPos(x0 - MARGIN, y0 - MARGIN, z0 - MARGIN));
		BlockPos b = h.absolutePos(new BlockPos(x1 + MARGIN, y1 + MARGIN, z1 + MARGIN));
		net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
				Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
				Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1);
		long sum = 0;
		for (var item : h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box)) {
			var stack = item.getItem();
			sum += (long) io.github.nagsfov.ocwo.sponge.OcwoSponges.itemLevels(stack) * stack.getCount() * Moisture.UNITS_PER_LEVEL;
		}
		return sum;
	}

	/** Units its blocks sent up to the clouds (evaporation), box plus {@link #MARGIN}. */
	static long sentUp(GameTestHelper h, int x0, int y0, int z0, int x1, int y1, int z1) {
		return io.github.nagsfov.ocwo.fluids.WaterReserves.sentUpIn(h.absolutePos(new BlockPos(x0 - MARGIN, y0 - MARGIN, z0 - MARGIN)),
				h.absolutePos(new BlockPos(x1 + MARGIN, y1 + MARGIN, z1 + MARGIN)));
	}

	/** Units of the moisture transfers its blocks made (box plus {@link #MARGIN}). */
	static long transferred(GameTestHelper h, int x0, int y0, int z0, int x1, int y1, int z1) {
		return io.github.nagsfov.ocwo.fluids.WaterReserves.transferredIn(h.absolutePos(new BlockPos(x0 - MARGIN, y0 - MARGIN, z0 - MARGIN)),
				h.absolutePos(new BlockPos(x1 + MARGIN, y1 + MARGIN, z1 + MARGIN)));
	}

	/** Liquid water + moisture of the box (plus {@link #MARGIN}), in units (use with the ledger, which has the clouds). */
	static long blockUnits(GameTestHelper h, int x0, int y0, int z0, int x1, int y1, int z1) {
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
