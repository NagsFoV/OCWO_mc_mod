package io.github.nagsfov.ocwo.gametest;

import io.github.nagsfov.ocwo.fluids.CustomFluidTicks;
import io.github.nagsfov.ocwo.fluids.WaterReserves;
import io.github.nagsfov.ocwo.moisture.Moisture;
import io.github.nagsfov.ocwo.moisture.MoistureSimulation;
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
 * Water ledger of the zones (03-humidite-et-etats-des-blocs.md, "Registre d'eau par zone"): visible water + block
 * moisture + reserves + clouds never changes, and nothing creates water outside the world generation.
 * <p>
 * Each test runs alone in its batch (own environment): the reserves and clouds are shared by everything in a zone.
 */
public class CheckLedgerGameTests {

	private static final int U = Moisture.UNITS_PER_LEVEL;
	/** The build: x 0..10, y 0..4, z 0..9 (relative). */
	private static final int X1 = 10, Y1 = 4, Z1 = 9;

	/** Like world generation: written into the chunk, no block change event. */
	private static void raw(GameTestHelper h, int x, int y, int z, BlockState s) {
		BlockPos p = h.absolutePos(new BlockPos(x, y, z));
		h.getLevel().getChunkAt(p).setBlockState(p, s, 0);
	}

	/** Zones touched by the build and the margin measured around it. */
	private static Set<Long> zones(GameTestHelper h) {
		Set<Long> zones = new LinkedHashSet<>();
		int m = WaterCount.MARGIN;
		for (int x : new int[]{-m, X1 + m})
			for (int z : new int[]{-m, Z1 + m}) zones.add(WaterReserves.zone(h.absolutePos(new BlockPos(x, 0, z))));
		return zones;
	}

	/** Reserves + clouds of the zones touched, in units. */
	private static long ledger(GameTestHelper h, Set<Long> zones) {
		WaterReserves r = WaterReserves.get(h.getLevel());
		long sum = 0;
		for (long z : zones) sum += r.reserveOf(z) + r.cloudsOf(z);
		return sum;
	}

	/** Everything the touched zones hold (counted like {@code /ocwo water ledger}), in units. */
	private static long zoneTotals(GameTestHelper h, Set<Long> zones) {
		return zoneTotals(h, zones, null);
	}

	/** Same, with the detail of each zone (diagnostics). */
	private static long zoneTotals(GameTestHelper h, Set<Long> zones, StringBuilder detail) {
		WaterReserves r = WaterReserves.get(h.getLevel());
		long sum = 0;
		for (long z : zones) {
			WaterReserves.ZoneCount c = r.count(h.getLevel(), z);
			sum += c.total();
			if (detail != null) detail.append(" [zone ").append(z).append(": ").append(c).append(']');
		}
		return sum;
	}

	/** Keeps every chunk of the touched zones loaded and ticking, so that a zone is always counted whole. */
	private static void loadZones(GameTestHelper h, Set<Long> zones) {
		ServerLevel level = h.getLevel();
		for (long z : zones) {
			int cx0 = WaterReserves.zoneX(z) << 2, cz0 = WaterReserves.zoneZ(z) << 2;
			for (int cx = cx0; cx < cx0 + 4; cx++)
				for (int cz = cz0; cz < cz0 + 4; cz++) {
					level.setChunkForced(cx, cz, true);
					level.getChunk(cx, cz); // loaded now (generated if needed), before anything is counted
				}
		}
	}

	private static int level(GameTestHelper h, int x, int y, int z) {
		return WaterCount.liquidLevels(h, new BlockPos(x, y, z));
	}

	/**
	 * A natural pool (x 1..5, z 1..5, two blocks deep) behind a wall of natural dirt (z 6) and a sealed empty pocket
	 * (x 2..4, z 7). Biome set to plains, so that the dirt has generation moisture. A stone block keeps some moisture,
	 * like the stone of a world saved before the stone stopped holding any. Then the player mines a dirt block of the
	 * wall: its generation moisture goes to the reserve (the tunnel is not flooded), the pool flows into the pocket and
	 * the reserve pays what it can, the rest really comes out of the pool (no invisible water). The stone gives its
	 * moisture to the reserve. Every tick: build water + reserves + clouds constant; at the start and the end: the
	 * whole zones counted like the command, constant.
	 */
	@GameTest(environment = "ocwo-gametest:ledger", maxTicks = 600, padding = 32)
	public void zoneTotalStaysConstant(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		Set<Long> zones = zones(h);
		loadZones(h, zones);
		// plains: dirt above sea level is damp (generation moisture), unlike in the test world's biome; the biome is put
		// back at the end (the next batches are built in the same place)
		int m = WaterCount.MARGIN;
		BlockPos a = h.absolutePos(new BlockPos(-m, -m, -m)), b = h.absolutePos(new BlockPos(X1 + m, Y1 + m, Z1 + m));
		String box = String.format("%d %d %d %d %d %d", Math.min(a.getX(), b.getX()), a.getY(), Math.min(a.getZ(), b.getZ()),
				Math.max(a.getX(), b.getX()), b.getY(), Math.max(a.getZ(), b.getZ()));
		String biome0 = level.getBiome(h.absolutePos(new BlockPos(3, 1, 3))).unwrapKey().map(k -> k.identifier().toString()).orElse("minecraft:the_void");
		Runnable restoreBiome = () -> level.getServer().getCommands().performPrefixedCommand(
				level.getServer().createCommandSourceStack().withSuppressedOutput(), "fillbiome " + box + " " + biome0);
		level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withSuppressedOutput(),
				"fillbiome " + box + " minecraft:plains");
		BlockState obs = Blocks.OBSIDIAN.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
		for (int x = 0; x <= X1; x++)
			for (int z = 0; z <= Z1; z++)
				for (int y = 0; y <= Y1; y++) level.setBlock(h.absolutePos(new BlockPos(x, y, z)), obs, 2);
		// the generated part: pool, dirt wall, pocket, written like the generation does
		for (int x = 1; x <= 5; x++)
			for (int y = 1; y <= 3; y++) {
				for (int z = 1; z <= 5; z++) raw(h, x, y, z, y <= 2 ? Blocks.WATER.defaultBlockState() : air);
				raw(h, x, y, 6, y <= 2 ? Blocks.DIRT.defaultBlockState() : obs);
			}
		for (int x = 2; x <= 4; x++)
			for (int y = 1; y <= 2; y++) raw(h, x, y, 7, air);
		MoistureSimulation sim = CustomFluidTicks.get(level).moisture();
		// a section that an earlier test already disturbed keeps its array (raw writes do not update it): the dirt gets
		// its generation moisture explicitly, as in a section never disturbed
		for (int x = 1; x <= 5; x++)
			for (int y = 1; y <= 2; y++) {
				BlockPos p = h.absolutePos(new BlockPos(x, y, 6));
				sim.setUnits(p, sim.equilibrium(p, Blocks.DIRT.defaultBlockState()));
			}
		BlockPos stone = new BlockPos(8, 1, 2), dirt = new BlockPos(3, 1, 6);
		raw(h, stone.getX(), stone.getY(), stone.getZ(), Blocks.STONE.defaultBlockState());
		sim.setUnits(h.absolutePos(stone), 20); // left over from before (stone holds no moisture any more)
		sim.activate(h.absolutePos(stone));

		long[] start = {-1};
		// every failure first puts the biome back (the next batches are built here)
		java.util.function.Consumer<String> fail = msg -> {
			start[0] = -1;
			restoreBiome.run();
			h.fail(msg);
		};

		// leak 1: a new ledger does not count the water already there a second time
		long fresh = new WaterReserves().reserveUnits(level, h.absolutePos(new BlockPos(3, 1, 3)));
		if (fresh != 0) fail.accept("a new zone reserve starts at " + fresh + " units instead of 0 (it doubles the water)");
		int eq = sim.equilibrium(h.absolutePos(dirt), Blocks.DIRT.defaultBlockState());
		if (eq <= 0) fail.accept("no generation moisture in the dirt (biome not changed?)");

		long[] zonesStart = {0};
		long[] ledgerStart = {0};
		int[] stoneStart = {0};
		StringBuilder startDetail = new StringBuilder();
		long[] reserveBeforeMining = {0};
		java.util.Map<String, String>[] snap = new java.util.Map[]{null};
		long[] prevLedger = {0}, prevPaid = {0};
		h.onEachTick(() -> {
			if (start[0] < 0) return;
			long now = WaterCount.blockUnits(h, 0, 0, 0, X1, Y1, Z1) + ledger(h, zones);
			java.util.Map<String, String> s = WaterCount.snapshot(h, 0, 0, 0, X1, Y1, Z1);
			if (now != start[0]) {
				// diagnostics: what changed during the tick that broke the balance
				if (snap[0] != null) WaterCount.printDiff(snap[0], s);
				System.out.println("[OCWO-DIFF] ledger " + prevLedger[0] + " -> " + ledger(h, zones) + ", reservoir levels paid "
						+ (io.github.nagsfov.ocwo.fluids.WaterReservoirs.levelsPaid - prevPaid[0]));
				fail.accept("water not conserved (units): " + start[0] + " -> " + now + " at tick " + h.getTick());
			}
			snap[0] = s;
			prevLedger[0] = ledger(h, zones);
			prevPaid[0] = io.github.nagsfov.ocwo.fluids.WaterReservoirs.levelsPaid;
			for (long z : zones)
				if (WaterReserves.get(level).reserveOf(z) < 0) fail.accept("negative reserve in zone " + z);
		});
		h.runAfterDelay(2, () -> {
			start[0] = WaterCount.blockUnits(h, 0, 0, 0, X1, Y1, Z1) + ledger(h, zones);
			zonesStart[0] = zoneTotals(h, zones, startDetail);
			ledgerStart[0] = ledger(h, zones);
			stoneStart[0] = sim.units(h.absolutePos(stone));
		});
		h.runAfterDelay(10, () -> {
			reserveBeforeMining[0] = ledger(h, zones);
			// the player mines a dirt block of the wall: the pool can now flow into the pocket
			level.setBlock(h.absolutePos(dirt), air, Block.UPDATE_ALL);
			long gained = ledger(h, zones) - reserveBeforeMining[0];
			// leak 2: the generation moisture goes back to the ground water, it does not come out in the tunnel
			if (gained != eq) fail.accept("mining the dirt gave " + gained + " units to the reserve, " + eq + " expected");
			if (level(h, 3, 1, 6) != 0) fail.accept("mining the dirt flooded the tunnel");
		});
		h.runAfterDelay(500, () -> {
			long reserve = ledger(h, zones);
			int pocket = 0, pool = 0;
			for (int x = 2; x <= 4; x++)
				for (int y = 1; y <= 2; y++) pocket += level(h, x, y, 7);
			for (int x = 1; x <= 5; x++)
				for (int z = 1; z <= 5; z++)
					for (int y = 1; y <= 2; y++) pool += level(h, x, y, z);
			if (pocket == 0) fail.accept("the pocket did not fill");
			if (sim.units(h.absolutePos(stone)) != 0) fail.accept("the stone still holds " + sim.units(h.absolutePos(stone)) + " units");
			// leak 3: the reserve pays only what it really has (the dirt and the stone came in), the rest came out of the
			// pool for real
			long had = ledgerStart[0] + eq + stoneStart[0];
			if (pocket * (long) U > had && pool >= 50 * 8) fail.accept("the pool kept its level (" + pool + " levels) although the reserve ("
					+ had + " units) could not pay for the pocket (" + pocket + " levels): invisible water");
			StringBuilder nowDetail = new StringBuilder();
			long zonesNow = zoneTotals(h, zones, nowDetail);
			if (zonesNow != zonesStart[0]) {
				System.out.println("[OCWO-TEST] ledger zones at start:" + startDetail);
				System.out.println("[OCWO-TEST] ledger zones now:     " + nowDetail);
				fail.accept("zone totals changed: " + zonesStart[0] + " -> " + zonesNow + " units");
			}
			System.out.println("[OCWO-TEST] ledger: pocket " + pocket + " levels, pool " + pool + " levels, reserve " + had + " -> "
					+ reserve + " units, zones " + zonesStart[0] + " -> " + zonesNow + " units");
			for (long z : zones) {
				int cx0 = WaterReserves.zoneX(z) << 2, cz0 = WaterReserves.zoneZ(z) << 2;
				for (int cx = cx0; cx < cx0 + 4; cx++)
					for (int cz = cz0; cz < cz0 + 4; cz++) level.setChunkForced(cx, cz, false);
			}
			start[0] = -1;
			restoreBiome.run();
			h.succeed();
		});
	}
}
