package io.github.nagsfov.ocwo.gametest;

import io.github.nagsfov.ocwo.fluids.CustomFluidTicks;
import io.github.nagsfov.ocwo.moisture.MoistureData;
import io.github.nagsfov.ocwo.moisture.MoistureSimulation;
import io.github.nagsfov.ocwo.moisture.WetBlocksPayload;
import io.github.nagsfov.ocwo.moisture.WetSync;
import io.github.nagsfov.ocwo.util.OcwoProfiler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Locale;

/**
 * Darkening of wet blocks, server side (Valentin, 19/11/2026; the client display cannot be tested here): what is sent
 * to the clients. Only visible blocks, only when their darkening step changes, steps relative to the generation
 * moisture, the blocks a change uncovers, a chunk's visible wet blocks with the chunk; the packet survives encoding.
 * The test world is a desert above the sea level: generation moisture dry, so the step is the moisture stage.
 */
public class CheckWetSyncGameTests {

	/** Its own batch: the moisture of the dirt replaced by stone goes to the zone reserve, which other tests count. */
	@GameTest(environment = "ocwo-gametest:wet_sync", maxTicks = 40)
	public void wetBlocksSentOnlyWhenVisibleAndChanged(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		MoistureSimulation sim = CustomFluidTicks.get(level).moisture();
		// a stone block of 5 x 3 x 5; dirt A on top (visible), dirt B inside (hidden on all six faces)
		for (int x = 0; x < 5; x++)
			for (int z = 0; z < 5; z++)
				for (int y = 1; y <= 3; y++) h.setBlock(new BlockPos(x, y, z), Blocks.STONE);
		BlockPos a = new BlockPos(2, 4, 2), b = new BlockPos(2, 2, 2);
		h.setBlock(a, Blocks.DIRT);
		h.setBlock(new BlockPos(2, 3, 2), Blocks.STONE);
		h.setBlock(b, Blocks.DIRT);
		BlockPos absA = h.absolutePos(a), absB = h.absolutePos(b);
		int cap = MoistureData.get(Blocks.DIRT.defaultBlockState()).capacity();
		h.runAfterDelay(3, () -> {
			if (WetSync.step(sim, absA, level.getBlockState(absA), 0) != 0) h.fail("dry dirt has a darkening step");
			int gen = sim.equilibrium(absA, level.getBlockState(absA));
			if (gen != 0) h.fail("the test world should give dirt no generation moisture here (desert): " + gen);
			// saturated: step 3, visible A queued, hidden B not
			sim.setUnits(absA, cap);
			sim.setUnits(absB, cap);
			if (WetSync.pendingStep(level, absA) != 3) h.fail("visible saturated dirt: step 3 expected, got " + WetSync.pendingStep(level, absA));
			if (WetSync.pendingStep(level, absB) != -1) h.fail("hidden dirt was queued");
			// a change inside a stage sends nothing; down to "mouillé" sends step 2
			WetSync.flush(level);
			sim.setUnits(absA, cap + 1);
			if (WetSync.pendingStep(level, absA) != -1) h.fail("no stage change, yet queued");
			sim.setUnits(absA, cap - 1);
			if (WetSync.pendingStep(level, absA) != 2) h.fail("mouillé: step 2 expected, got " + WetSync.pendingStep(level, absA));
			WetSync.flush(level);
			if (WetSync.pendingCount(level) != 0) h.fail("the end of the tick should send everything");
			// with the chunk: A (step 2), not B
			LevelChunk chunk = level.getChunkAt(absA);
			int[] entries = WetSync.chunkEntries(level, chunk);
			boolean hasA = false, hasB = false;
			for (int e : entries) {
				int y = WetBlocksPayload.sectionY(e) * 16 + (WetBlocksPayload.cell(e) >> 8);
				int x = chunk.getPos().getMinBlockX() + (WetBlocksPayload.cell(e) & 15);
				int z = chunk.getPos().getMinBlockZ() + ((WetBlocksPayload.cell(e) >> 4) & 15);
				if (x == absA.getX() && y == absA.getY() && z == absA.getZ()) hasA = WetBlocksPayload.step(e) == 2;
				if (x == absB.getX() && y == absB.getY() && z == absB.getZ()) hasB = true;
			}
			if (!hasA) h.fail("the chunk should carry A at step 2");
			if (hasB) h.fail("the chunk carries the hidden B");
			// the stone above B is mined: B is uncovered and sent; A replaced by stone: back to step 0
			level.setBlock(h.absolutePos(new BlockPos(2, 3, 2)), Blocks.AIR.defaultBlockState(), 3);
			if (WetSync.pendingStep(level, absB) != 3) h.fail("uncovered B: step 3 expected, got " + WetSync.pendingStep(level, absB));
			level.setBlock(absA, Blocks.STONE.defaultBlockState(), 3);
			if (WetSync.pendingStep(level, absA) != 0) h.fail("A replaced: step 0 expected, got " + WetSync.pendingStep(level, absA));
			WetSync.flush(level);
			// packet round trip
			WetBlocksPayload p = new WetBlocksPayload(chunk.getPos().x(), chunk.getPos().z(), true, entries);
			ByteBuf buf = Unpooled.buffer();
			WetBlocksPayload.STREAM_CODEC.encode(buf, p);
			if (!WetBlocksPayload.STREAM_CODEC.decode(buf).sameAs(p)) h.fail("wet blocks packet changed on the way");
			int packed = WetBlocksPayload.pack(-4, 4095, 3);
			if (WetBlocksPayload.sectionY(packed) != -4 || WetBlocksPayload.cell(packed) != 4095 || WetBlocksPayload.step(packed) != 3)
				h.fail("entry packing");
			h.succeed();
		});
	}

	/**
	 * Cost: a pool of 12 x 12 on a dirt floor, with fast moisture ticks, for 600 ticks. Reports how many moisture
	 * changes there were, how many reached the clients' queue, and the time of the sending code. Alone in its batch.
	 */
	@GameTest(environment = "ocwo-gametest:wet_sync", maxTicks = 800, padding = 16)
	public void wetSyncCost(GameTestHelper h) {
		ServerLevel level = h.getLevel();
		int n = 12;
		for (int x = 0; x <= n + 1; x++)
			for (int z = 0; z <= n + 1; z++) {
				boolean inside = x >= 1 && x <= n && z >= 1 && z <= n;
				h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
				h.setBlock(new BlockPos(x, 1, z), Blocks.DIRT);
				h.setBlock(new BlockPos(x, 2, z), inside ? Blocks.WATER : Blocks.DIRT);
				h.setBlock(new BlockPos(x, 3, z), inside ? Blocks.WATER : Blocks.AIR);
			}
		long[] queued0 = new long[1];
		h.runAfterDelay(2, () -> {
			OcwoProfiler.start(level);
			queued0[0] = WetSync.queued;
		});
		h.runAfterDelay(602, () -> {
			String report = OcwoProfiler.sectionsReport();
			OcwoProfiler.stop();
			// what a player who arrives now would get with these chunks (once to warm up, then the mean of 10)
			int entries = 0;
			java.util.Set<Long> chunks = new java.util.HashSet<>();
			for (int x = 0; x <= n + 1; x += 4)
				for (int z = 0; z <= n + 1; z += 4) chunks.add(net.minecraft.world.level.ChunkPos.pack(h.absolutePos(new BlockPos(x, 1, z))));
			long t0 = 0;
			for (int run = 0; run <= 10; run++) {
				if (run == 1) t0 = System.nanoTime();
				entries = 0;
				for (long c : chunks)
					entries += WetSync.chunkEntries(level, level.getChunk(net.minecraft.world.level.ChunkPos.getX(c), net.minecraft.world.level.ChunkPos.getZ(c))).length;
			}
			double chunkMs = (System.nanoTime() - t0) / 1e6 / 10;
			System.out.println(String.format(Locale.ROOT,
					"[OCWO-TEST] wet sync cost over 600 ticks: %d step changes queued for the clients; with the chunks (%d): %d visible wet blocks, %.2f ms; %s",
					WetSync.queued - queued0[0], chunks.size(), entries, chunkMs, report));
			if (entries == 0) h.fail("the wet dirt around the pool should be visible and wet");
			h.succeed();
		});
	}
}
