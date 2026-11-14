package io.github.nagsfov.ocwo.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Series of communicating vessels (Valentin, 13/11/2026: large vessels stop before balancing).
 * Launch with gradlew :fabric:runGameTestVessels (each case runs alone in its own batch).
 * <p>
 * Two vessels w x w, of the given height, joined at the bottom by a tunnel (tw wide, th high, len long), in
 * obsidian. The left vessel is filled (all at once, or poured progressively), the right one is empty. A case
 * passes when both surfaces stay within one level of each other for 100 ticks; it fails as "stalled" when the
 * surfaces are unbalanced and nothing moved for 200 ticks; water is checked at every tick.
 */
public class VesselsGameTests {

	private static int liquid(GameTestHelper h, int x, int y, int z) {
		FluidState fs = h.getLevel().getFluidState(h.absolutePos(new BlockPos(x, y, z)));
		return fs.getType().isSame(Fluids.WATER) ? fs.getAmount() : 0;
	}

	private static long total(GameTestHelper h, int x1, int y1, int z1) {
		long sum = 0;
		for (int x = 0; x <= x1; x++)
			for (int y = 0; y <= y1; y++)
				for (int z = 0; z <= z1; z++)
					sum += liquid(h, x, y, z);
		return sum;
	}

	/** Surface height (y * 8 + level) of a vessel: the highest water of its columns. */
	private static int surface(GameTestHelper h, int x0, int w, int height) {
		int best = -1;
		for (int x = x0; x < x0 + w; x++)
			for (int z = 1; z <= w; z++)
				for (int y = height; y >= 1; y--) {
					int l = liquid(h, x, y, z);
					if (l > 0) {
						best = Math.max(best, y * 8 + l);
						break;
					}
				}
		return best;
	}

	/**
	 * @param fill        water height in the left vessel (all at once), or poured progressively if {@code pour}
	 */
	private static void run(GameTestHelper h, String name, int w, int height, int tw, int th, int len, int fill, boolean pour) {
		ServerLevel level = h.getLevel();
		int quiet = Block.UPDATE_CLIENTS;
		int xR = w + len + 1;              // first x of the right vessel
		int xMax = xR + w;
		int zMax = Math.max(w, tw) + 1;
		BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();
		BlockState air = Blocks.AIR.defaultBlockState();
		BlockState water = Blocks.WATER.defaultBlockState();
		int tz0 = 1 + (w - tw) / 2;
		for (int x = 0; x <= xMax; x++)
			for (int z = 0; z <= zMax; z++)
				for (int y = 0; y <= height + 1; y++) {
					boolean inZ = z >= 1 && z <= w;
					boolean left = x >= 1 && x <= w && inZ && y >= 1;
					boolean right = x >= xR && x < xR + w && inZ && y >= 1;
					boolean tunnel = x > w && x < xR && z >= tz0 && z < tz0 + tw && y >= 1 && y <= th;
					BlockState s = obsidian;
					if (left) s = !pour && y <= fill ? water : air;
					else if (right || tunnel) s = air;
					level.setBlock(h.absolutePos(new BlockPos(x, y, z)), s, quiet);
				}
		for (int z = 1; z <= w; z++)
			for (int y = 1; y <= th; y++)
				level.scheduleTick(h.absolutePos(new BlockPos(w, y, z)), Fluids.WATER, 1);

		long[] expected = {total(h, xMax, height + 1, zMax)};
		int pourTarget = pour ? fill * w * w : 0;
		int[] poured = {0};
		int[] last = {-1, -1};
		long[] since = {0};
		long start = h.getTick();
		h.onEachTick(() -> {
			long t = h.getTick();
			// progressive pouring: one block of water at the top of the left vessel every 4 ticks
			if (pour && poured[0] < pourTarget && t % 4 == 0) {
				BlockPos top = new BlockPos(1 + (poured[0] % w), height, 1 + ((poured[0] / w) % w));
				if (liquid(h, top.getX(), top.getY(), top.getZ()) == 0) {
					level.setBlock(h.absolutePos(top), water, Block.UPDATE_ALL);
					poured[0]++;
					expected[0] += 8;
				}
			}
			// what the slow check does near a player in game: queue a block of the body now and then
			if (t % 50 == 0) io.github.nagsfov.ocwo.fluids.CustomFluidTicks.get(level).enqueuePressure(h.absolutePos(new BlockPos(1, 1, 1)), Fluids.WATER);
			if (t % 10 != 0) return;
			long now = total(h, xMax, height + 1, zMax);
			if (now != expected[0]) {
				h.fail(name + ": water not conserved: " + expected[0] + " levels expected, " + now + " now");
				return;
			}
			int l = surface(h, 1, w, height), r = surface(h, xR, w, height);
			if (l != last[0] || r != last[1]) {
				last[0] = l;
				last[1] = r;
				since[0] = t;
			}
			boolean pouring = pour && poured[0] < pourTarget;
			if (!pouring && Math.abs(l - r) <= 1 && t - since[0] >= 100) {
				// the inspection used by the debug panel must agree: complete and balanced
				io.github.nagsfov.ocwo.fluids.PressureSearch.Inspection in = io.github.nagsfov.ocwo.fluids.PressureSearch.inspect(
						level, h.absolutePos(new BlockPos(1, 1, 1)), 65_536);
				if (!in.balanced()) {
					h.fail(name + ": surfaces equal but the inspection says not balanced (high " + in.highHeight() + ", low "
							+ in.lowHeight() + ", " + in.columns() + " columns, complete " + in.complete() + ")");
					return;
				}
				System.out.println(String.format("[OCWO-VESSELS] %-34s BALANCED at tick %5d (surfaces %d / %d)", name, since[0] - start, l, r));
				h.succeed();
			} else if (!pouring && Math.abs(l - r) > 1 && t - since[0] >= 200) {
				System.out.println(String.format("[OCWO-VESSELS] %-34s STALLED since tick %5d (left %d, right %d)", name, since[0] - start, l, r));
				StringBuilder sb = new StringBuilder();
				for (int x = 1; x < xR + 1; x++) {
					sb.append(" x").append(x).append(':');
					for (int y = 1; y <= Math.min(height, 6); y++) {
						FluidState fs = h.getLevel().getFluidState(h.absolutePos(new BlockPos(x, y, 1)));
						sb.append(fs.isEmpty() ? "." : fs.getAmount() + (PressureSearchFalling.is(fs) ? "f" : "")).append(' ');
					}
				}
				System.out.println("[OCWO-VESSELS]   cells (x: y1..y6 at z=1):" + sb);
				io.github.nagsfov.ocwo.fluids.PressureSearch.Inspection in = io.github.nagsfov.ocwo.fluids.PressureSearch.inspect(
						level, h.absolutePos(new BlockPos(1, 1, 1)), 65_536);
				System.out.println("[OCWO-VESSELS]   inspect(1,1,1): high " + in.highHeight() + " low " + in.lowHeight() + " columns "
						+ in.columns() + " complete " + in.complete() + "; pressure queue "
						+ io.github.nagsfov.ocwo.fluids.CustomFluidTicks.get(level).pressureQueueSize());
				h.fail(name + ": stalled since tick " + (since[0] - start) + ", left surface " + l + ", right " + r);
			}
		});
	}

	// width x height, tunnel 1x1 long 5, filled at once (water up to height - 2)
	@GameTest(environment = "ocwo-gametest:vessels_01", maxTicks = 4000, padding = 40)
	public void w1h10(GameTestHelper h) { run(h, "w1 h10 t1x1 l5", 1, 10, 1, 1, 5, 8, false); }

	@GameTest(environment = "ocwo-gametest:vessels_02", maxTicks = 4000, padding = 40)
	public void w1h40(GameTestHelper h) { run(h, "w1 h40 t1x1 l5", 1, 40, 1, 1, 5, 38, false); }

	@GameTest(environment = "ocwo-gametest:vessels_03", maxTicks = 6000, padding = 40)
	public void w3h10(GameTestHelper h) { run(h, "w3 h10 t1x1 l5", 3, 10, 1, 1, 5, 8, false); }

	@GameTest(environment = "ocwo-gametest:vessels_04", maxTicks = 8000, padding = 40)
	public void w3h20(GameTestHelper h) { run(h, "w3 h20 t1x1 l5", 3, 20, 1, 1, 5, 18, false); }

	@GameTest(environment = "ocwo-gametest:vessels_05", maxTicks = 12000, padding = 40)
	public void w3h40(GameTestHelper h) { run(h, "w3 h40 t1x1 l5", 3, 40, 1, 1, 5, 38, false); }

	@GameTest(environment = "ocwo-gametest:vessels_06", maxTicks = 12000, padding = 40)
	public void w5h20(GameTestHelper h) { run(h, "w5 h20 t1x1 l5", 5, 20, 1, 1, 5, 18, false); }

	@GameTest(environment = "ocwo-gametest:vessels_07", maxTicks = 16000, padding = 40)
	public void w5h40(GameTestHelper h) { run(h, "w5 h40 t1x1 l5", 5, 40, 1, 1, 5, 38, false); }

	@GameTest(environment = "ocwo-gametest:vessels_08", maxTicks = 16000, padding = 40)
	public void w9h20(GameTestHelper h) { run(h, "w9 h20 t1x1 l5", 9, 20, 1, 1, 5, 18, false); }

	@GameTest(environment = "ocwo-gametest:vessels_09", maxTicks = 20000, padding = 40)
	public void w12h20(GameTestHelper h) { run(h, "w12 h20 t1x1 l5", 12, 20, 1, 1, 5, 18, false); }

	// tunnel sections (w5 h20, length 5)
	@GameTest(environment = "ocwo-gametest:vessels_10", maxTicks = 12000, padding = 40)
	public void t1x2(GameTestHelper h) { run(h, "w5 h20 t1x2 l5", 5, 20, 1, 2, 5, 18, false); }

	@GameTest(environment = "ocwo-gametest:vessels_11", maxTicks = 12000, padding = 40)
	public void t2x2(GameTestHelper h) { run(h, "w5 h20 t2x2 l5", 5, 20, 2, 2, 5, 18, false); }

	@GameTest(environment = "ocwo-gametest:vessels_12", maxTicks = 12000, padding = 40)
	public void t3x3(GameTestHelper h) { run(h, "w5 h20 t3x3 l5", 5, 20, 3, 3, 5, 18, false); }

	// tunnel lengths (w5 h20, 1x1)
	@GameTest(environment = "ocwo-gametest:vessels_13", maxTicks = 12000, padding = 40)
	public void len1(GameTestHelper h) { run(h, "w5 h20 t1x1 l1", 5, 20, 1, 1, 1, 18, false); }

	@GameTest(environment = "ocwo-gametest:vessels_14", maxTicks = 12000, padding = 40)
	public void len20(GameTestHelper h) { run(h, "w5 h20 t1x1 l20", 5, 20, 1, 1, 20, 18, false); }

	// progressive pouring
	@GameTest(environment = "ocwo-gametest:vessels_15", maxTicks = 12000, padding = 40)
	public void pour3(GameTestHelper h) { run(h, "w3 h20 t1x1 l5 poured", 3, 20, 1, 1, 5, 18, true); }

	@GameTest(environment = "ocwo-gametest:vessels_16", maxTicks = 20000, padding = 40)
	public void pour9(GameTestHelper h) { run(h, "w9 h20 t1x1 l5 poured", 9, 20, 1, 1, 5, 18, true); }

	private static final class PressureSearchFalling {
		static boolean is(FluidState fs) {
			return io.github.nagsfov.ocwo.fluids.PressureSearch.isFalling(fs);
		}
	}
}
