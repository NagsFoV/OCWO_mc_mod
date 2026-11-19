package io.github.nagsfov.ocwo.gametest;

import io.github.nagsfov.ocwo.fluids.CustomFluidTicks;
import io.github.nagsfov.ocwo.fluids.WaterReserves;
import io.github.nagsfov.ocwo.moisture.MoistureSimulation;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Evaporation of moisture transfers (03-humidite-et-etats-des-blocs.md, "Évaporation"): about 2 % of every transfer in
 * a temperate biome, more where it is hot and dry, less where it is cold; it goes to the clouds of the zone, with the
 * fractions of units kept per zone, and nothing is lost. Alone in its batch: the clouds are shared by a zone.
 */
public class CheckEvaporationGameTests {

	private static final int X1 = 6, Y1 = 6, Z1 = 6;

	private static void cmd(ServerLevel level, String command) {
		level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack().withSuppressedOutput(), command);
	}

	/** Biome factor at rel once the biome around it is set to this biome; the biome is put back. */
	private static double factorIn(GameTestHelper h, BlockPos rel, String biome) {
		ServerLevel level = h.getLevel();
		BlockPos p = h.absolutePos(rel);
		String before = level.getBiome(p).unwrapKey().map(k -> k.identifier().toString()).orElse("minecraft:the_void");
		// the biome read at a position may come from a neighbouring 4 x 4 x 4 cell (biome blending): set them all
		String cell = (p.getX() - 4) + " " + (p.getY() - 4) + " " + (p.getZ() - 4) + " " + (p.getX() + 4) + " " + (p.getY() + 4) + " " + (p.getZ() + 4);
		cmd(level, "fillbiome " + cell + " " + biome);
		double f = CustomFluidTicks.get(level).moisture().evaporationFactor(p);
		cmd(level, "fillbiome " + cell + " " + before);
		return f;
	}

	/**
	 * A pool (3 blocks deep) over a floor of dirt (it turns to mud), over an empty chamber: the floor soaks up the pool
	 * and passes moisture around (sand would fall into the chamber). Every tick: water of the box + what its blocks sent up constant. At
	 * the end: the clouds of the zones grew by at least what went up (other leftovers may add to them); what went up is the rule's 2 % of the transfers
	 * (times the factor of the biome here), within the rounding of the fractions; the biome factors are right.
	 */
	@GameTest(environment = "ocwo-gametest:evaporation", maxTicks = 700, padding = 32)
	public void transfersEvaporateToTheClouds(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		MoistureSimulation sim = CustomFluidTicks.get(level).moisture();
		// biome factors: temperate 1, hot and dry 3.5, cold 0.5 (the biome is put back each time)
		String biome0 = level.getBiome(h.absolutePos(new BlockPos(3, 3, 3))).unwrapKey().map(k -> k.identifier().toString()).orElse("?");
		double plains = factorIn(h, new BlockPos(3, 3, 3), "minecraft:plains");
		double desert = factorIn(h, new BlockPos(3, 3, 3), "minecraft:desert");
		double snowy = factorIn(h, new BlockPos(3, 3, 3), "minecraft:snowy_plains");
		if (Math.abs(plains - 1.0) > 1e-6 || Math.abs(desert - 3.5) > 1e-6 || Math.abs(snowy - 0.5) > 1e-6)
			h.fail(String.format(Locale.ROOT, "biome factors: plains %.3f (1), desert %.3f (3.5), snowy plains %.3f (0.5)", plains, desert, snowy));
		String biome1 = level.getBiome(h.absolutePos(new BlockPos(3, 3, 3))).unwrapKey().map(k -> k.identifier().toString()).orElse("?");
		if (!biome1.equals(biome0)) h.fail("the biome was not put back: " + biome0 + " -> " + biome1);
		System.out.println("[OCWO-TEST] evaporation: biome of the test world here " + biome0 + ", factor "
				+ sim.evaporationFactor(h.absolutePos(new BlockPos(3, 3, 3))));

		BlockState obs = Blocks.OBSIDIAN.defaultBlockState();
		for (int x = 0; x <= X1; x++)
			for (int z = 0; z <= Z1; z++)
				for (int y = 0; y <= Y1; y++) {
					boolean inside = x >= 1 && x <= X1 - 1 && z >= 1 && z <= Z1 - 1 && y >= 1;
					BlockState s = obs;
					if (inside && y == 1) s = Blocks.AIR.defaultBlockState();
					else if (inside && y == 2) s = Blocks.DIRT.defaultBlockState();
					else if (inside && y <= 5) s = Blocks.WATER.defaultBlockState();
					else if (inside) s = Blocks.AIR.defaultBlockState();
					level.setBlock(h.absolutePos(new BlockPos(x, y, z)), s, 2);
				}
		Set<Long> zones = new LinkedHashSet<>();
		int m = WaterCount.MARGIN;
		for (int x : new int[]{-m, X1 + m})
			for (int z : new int[]{-m, Z1 + m}) zones.add(WaterReserves.zone(h.absolutePos(new BlockPos(x, 0, z))));
		WaterReserves ledger = WaterReserves.get(level);
		double rate = 0.02 * sim.evaporationFactor(h.absolutePos(new BlockPos(3, 2, 3)));

		long[] start = {-1}, clouds0 = {0}, up0 = {0}, moved0 = {0};
		h.onEachTick(() -> {
			if (start[0] < 0) return;
			long now = WaterCount.units(h, 0, 0, 0, X1, Y1, Z1);
			if (now != start[0]) h.fail("water not conserved (units, evaporation counted): " + start[0] + " -> " + now + " at tick " + h.getTick());
		});
		h.runAfterDelay(1, () -> {
			start[0] = WaterCount.units(h, 0, 0, 0, X1, Y1, Z1);
			for (long z : zones) clouds0[0] += ledger.cloudsOf(z);
			up0[0] = WaterCount.sentUp(h, 0, 0, 0, X1, Y1, Z1);
			moved0[0] = WaterCount.transferred(h, 0, 0, 0, X1, Y1, Z1);
		});
		h.runAfterDelay(600, () -> {
			long clouds = -clouds0[0];
			for (long z : zones) clouds += ledger.cloudsOf(z);
			long up = WaterCount.sentUp(h, 0, 0, 0, X1, Y1, Z1) - up0[0];
			long moved = WaterCount.transferred(h, 0, 0, 0, X1, Y1, Z1) - moved0[0];
			long chamber = 0;
			for (int x = 1; x <= X1 - 1; x++)
				for (int z = 1; z <= Z1 - 1; z++) chamber += WaterCount.liquidLevels(h, new BlockPos(x, 1, z));
			System.out.println(String.format(Locale.ROOT, "[OCWO-TEST] evaporation: %d units moved, %d went up (%.2f %%, expected %.2f %%), clouds +%d, chamber %d levels",
					moved, up, moved == 0 ? 0 : 100.0 * up / moved, rate * 100, clouds, chamber));
			if (moved < 1000) h.fail("too few transfers to measure: " + moved + " units");
			if (clouds < up) h.fail("the clouds grew by " + clouds + " units, " + up + " went up");
			// fractions kept per zone (shared with anything else in the zone): a few units of rounding at most
			double expected = rate * moved;
			if (Math.abs(up - expected) > Math.max(zones.size() + 2, 0.05 * expected)) h.fail(String.format(Locale.ROOT, "%d units went up, %.1f expected", up, expected));
			h.succeed();
		});
	}
}
