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
			if (!p.head().getFirst().startsWith("ocwo.debug.head.fluid")) h.fail("head in water expected: " + p.head());
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

	private static void check(GameTestHelper h, List<String> lines, String group) {
		if (lines.isEmpty()) h.fail(group + ": no line");
		for (String l : lines) if (!l.startsWith("ocwo.debug.")) h.fail(group + ": not a translation key: " + l);
	}
}
