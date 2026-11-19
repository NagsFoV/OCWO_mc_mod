package io.github.nagsfov.ocwo.gametest;

import io.github.nagsfov.ocwo.debug.DebugPayload;
import io.github.nagsfov.ocwo.debug.DebugSubscribePayload;
import io.github.nagsfov.ocwo.debug.OcwoDebug;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * OCWO lines of the F3 debug screen, server side (the client display cannot be tested here): content of the three
 * groups, packet encoding, subscription bookkeeping, simulation group reserved to operators.
 */
public class CheckDebugGameTests {

	@GameTest(maxTicks = 40)
	public void debugLines(GameTestHelper h) {
		for (int x = 0; x < 3; x++)
			for (int z = 0; z < 3; z++) {
				h.setBlock(new BlockPos(x, 1, z), Blocks.DIRT);
				for (int y = 2; y <= 4; y++) h.setBlock(new BlockPos(x, y, z), Blocks.WATER);
			}
		ServerPlayer player = h.makeMockServerPlayerInLevel();
		player.teleportTo(h.absolutePos(new BlockPos(1, 2, 1)).getX() + 0.5, h.absolutePos(new BlockPos(1, 2, 1)).getY() + 0.1,
				h.absolutePos(new BlockPos(1, 2, 1)).getZ() + 0.5);
		player.setXRot(90); // looking down at the dirt
		h.runAfterDelay(5, () -> {
			int all = DebugSubscribePayload.HEAD | DebugSubscribePayload.BLOCK | DebugSubscribePayload.SIM;
			DebugPayload p = OcwoDebug.build(h.getLevel(), player, all);
			check(h, p.head(), "head");
			check(h, p.block(), "block");
			// a mock player is no operator: the simulation group only says so
			if (!p.sim().equals(List.of("ocwo.debug.sim.operators"))) h.fail("simulation group for a non operator: " + p.sim());
			if (p.head().stream().noneMatch(l -> l.startsWith("ocwo.debug.head.fluid"))) h.fail("head in water expected: " + p.head());
			// only the requested groups are computed
			DebugPayload onlyHead = OcwoDebug.build(h.getLevel(), player, DebugSubscribePayload.HEAD);
			if (onlyHead.head().isEmpty() || !onlyHead.block().isEmpty() || !onlyHead.sim().isEmpty()) h.fail("groups not filtered");
			// packet round trip
			ByteBuf buf = Unpooled.buffer();
			DebugPayload.STREAM_CODEC.encode(buf, p);
			DebugPayload back = DebugPayload.STREAM_CODEC.decode(buf);
			if (!back.equals(p)) h.fail("debug packet changed on the way: " + back);
			ByteBuf buf2 = Unpooled.buffer();
			DebugSubscribePayload.STREAM_CODEC.encode(buf2, new DebugSubscribePayload(all));
			if (DebugSubscribePayload.STREAM_CODEC.decode(buf2).mask() != all) h.fail("subscription packet changed on the way");
			// subscription bookkeeping
			int before = OcwoDebug.subscribers();
			OcwoDebug.onSubscribe(player, all);
			if (OcwoDebug.subscribers() != before + 1) h.fail("subscription not recorded");
			OcwoDebug.onSubscribe(player, 0);
			if (OcwoDebug.subscribers() != before) h.fail("unsubscription not recorded");
			System.out.println("[OCWO-TEST] debug lines: " + p.head() + " " + p.block());
			h.succeed();
		});
	}

	/**
	 * Reservoirs group and display (Valentin, 19/11/2026): a lake of natural water (written like the generation) is
	 * seen as a reservoir, a saturated gravel block as a saturated cluster with its outlet; the group and the display
	 * are for operators; the display packet carries the lake's surface and the saturated block, and survives encoding.
	 */
	@GameTest(maxTicks = 40)
	public void reservoirLinesAndView(GameTestHelper h) {
		net.minecraft.server.level.ServerLevel level = h.getLevel();
		// a lake of 4 x 4 x 2 natural water in obsidian, open to the sky (written straight into the chunk: natural)
		for (int x = 0; x <= 5; x++)
			for (int z = 0; z <= 5; z++)
				for (int y = 1; y <= 3; y++) {
					boolean inside = x >= 1 && x <= 4 && z >= 1 && z <= 4 && y >= 2;
					BlockPos p = h.absolutePos(new BlockPos(x, y, z));
					level.getChunkAt(p).setBlockState(p, inside ? Blocks.WATER.defaultBlockState() : Blocks.OBSIDIAN.defaultBlockState(), 0);
				}
		// a saturated gravel block on obsidian, an empty cell beside it (its outlet)
		h.setBlock(new BlockPos(7, 1, 2), Blocks.OBSIDIAN);
		h.setBlock(new BlockPos(8, 1, 2), Blocks.OBSIDIAN);
		h.setBlock(new BlockPos(7, 2, 2), Blocks.GRAVEL);
		var moisture = io.github.nagsfov.ocwo.fluids.CustomFluidTicks.get(level).moisture();
		BlockPos gravel = h.absolutePos(new BlockPos(7, 2, 2));
		int cap = io.github.nagsfov.ocwo.moisture.MoistureData.get(Blocks.GRAVEL.defaultBlockState()).capacity();
		ServerPlayer player = h.makeMockServerPlayerInLevel();
		BlockPos above = h.absolutePos(new BlockPos(2, 5, 2)); // (under the barrier ceiling of the test area)
		player.teleportTo(above.getX() + 0.5, above.getY(), above.getZ() + 0.5);
		player.setXRot(90); // looking down at the lake
		h.runAfterDelay(5, () -> {
			moisture.setUnits(gravel, cap);
			// not an operator: the group and the display say so
			DebugPayload p = OcwoDebug.build(level, player, DebugSubscribePayload.RESERVOIRS);
			if (!p.reservoirs().equals(List.of("ocwo.debug.res.operators"))) h.fail("reservoirs group for a non operator: " + p.reservoirs());
			// the content, as an operator would get it (a mock player cannot be made an operator here)
			try {
				List<String> lines = OcwoDebug.reservoirLines(level, player);
				check(h, lines, "reservoirs");
				String body = lines.get(0);
				if (!body.startsWith("ocwo.debug.res.body.reservoir" + DebugPayload.SEP + "16" + DebugPayload.SEP + "16"))
					h.fail("the lake should be a reservoir of 16 surface blocks that can all go down: " + body + " (water at the centre: " + level.getFluidState(h.absolutePos(new BlockPos(2, 3, 2))) + ", eye " + player.getEyePosition() + ", view " + player.getViewVector(1.0F) + ", hit " + clipInfo(level, player) + ")");
				if (lines.stream().noneMatch(l -> l.startsWith("ocwo.debug.res.zone"))) h.fail("no zone line: " + lines);
				// the saturated gravel: one block, its outlet beside it
				var cluster = moisture.inspectCluster(gravel);
				if (cluster == null || cluster.blocks() != 1 || cluster.outlet() == null)
					h.fail("saturated gravel: cluster of 1 block with an outlet expected: " + cluster);
				// the display, around the player
				var view = OcwoDebug.view(level, player);
				// (other tests of the batch may have water nearby: the lake's 16 surface blocks must be there)
				java.util.Set<Long> shown = new java.util.HashSet<>();
				for (long c : view.surface()) shown.add(c);
				for (int x = 1; x <= 4; x++)
					for (int z = 1; z <= 4; z++)
						if (!shown.contains(h.absolutePos(new BlockPos(x, 3, z)).asLong()))
							h.fail("display: surface block of the lake missing at " + x + " 3 " + z + " (" + view.surface().length + " shown)");
				boolean hasGravel = false;
				for (long c : view.saturated()) hasGravel |= c == gravel.asLong();
				if (!hasGravel) h.fail("display: the saturated gravel is missing");
				ByteBuf buf = Unpooled.buffer();
				io.github.nagsfov.ocwo.debug.ReservoirViewPayload.STREAM_CODEC.encode(buf, view);
				if (!io.github.nagsfov.ocwo.debug.ReservoirViewPayload.STREAM_CODEC.decode(buf).sameAs(view)) h.fail("display packet changed on the way");
				if (!OcwoDebug.setView(player, true) || !OcwoDebug.isViewing(player)) h.fail("display not turned on");
				OcwoDebug.setView(player, false);
				if (OcwoDebug.isViewing(player)) h.fail("display not turned off");
				System.out.println("[OCWO-TEST] reservoir lines: " + lines + "; display: " + view.surface().length + " surface, "
						+ view.saturated().length + " saturated, " + view.sources().length + " sources");
			} finally {
				OcwoDebug.onPlayerLeft(player.getUUID());
			}
			h.succeed();
		});
	}

	private static String clipInfo(net.minecraft.server.level.ServerLevel level, ServerPlayer player) {
		var eye = player.getEyePosition();
		var end = eye.add(player.getViewVector(1.0F).scale(32));
		var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, end, net.minecraft.world.level.ClipContext.Block.OUTLINE,
				net.minecraft.world.level.ClipContext.Fluid.ANY, player));
		return hit.getType() + " " + hit.getBlockPos().toShortString() + " " + level.getBlockState(hit.getBlockPos());
	}

	private static void check(GameTestHelper h, List<String> lines, String group) {
		if (lines.isEmpty()) h.fail(group + ": no line");
		for (String l : lines) if (!l.startsWith("ocwo.debug.")) h.fail(group + ": not a translation key: " + l);
	}
}
