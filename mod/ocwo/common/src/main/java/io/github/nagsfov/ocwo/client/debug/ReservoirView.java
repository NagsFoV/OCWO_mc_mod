package io.github.nagsfov.ocwo.client.debug;

import io.github.nagsfov.ocwo.debug.ReservoirViewPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;

/**
 * Display of the reservoirs in the world ({@code /ocwo debug reservoirs}), drawn with dust particles (no rendering
 * code of our own: it works the same with Sodium). Blue above the surface of the abstract reservoirs (lakes and seas
 * that pay out of their surface), orange above the saturated wet ground, a green column at each zone source. The
 * server sends the positions every second while the display is on, and an empty list to turn it off.
 */
public final class ReservoirView {

	private static final DustParticleOptions SURFACE = new DustParticleOptions(0x3F76E4, 1.0F);
	private static final DustParticleOptions GROUND = new DustParticleOptions(0xFF9020, 0.8F);
	private static final DustParticleOptions SOURCE = new DustParticleOptions(0x30FF60, 1.5F);
	/** Ticks between two puffs of particles (they last about as long). */
	private static final int PERIOD = 10;
	/** Blocks around the camera that get particles. */
	private static final double RANGE = 48.0;

	private static volatile ReservoirViewPayload current = ReservoirViewPayload.EMPTY;
	private static int ticks;

	private ReservoirView() {
	}

	public static void receive(ReservoirViewPayload payload) {
		current = payload;
	}

	public static void reset() {
		current = ReservoirViewPayload.EMPTY;
	}

	/** Every client tick. */
	public static void tick(Minecraft mc) {
		ReservoirViewPayload view = current;
		if (view.isEmpty() || mc.level == null || mc.player == null || mc.isPaused()) return;
		if (++ticks % PERIOD != 0) return;
		double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
		double r2 = RANGE * RANGE;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (long c : view.surface()) {
			m.set(BlockPos.getX(c), BlockPos.getY(c), BlockPos.getZ(c));
			if (m.distToCenterSqr(px, py, pz) > r2) continue;
			mc.level.addParticle(SURFACE, m.getX() + 0.5, m.getY() + 1.05, m.getZ() + 0.5, 0, 0, 0);
		}
		for (long c : view.saturated()) {
			m.set(BlockPos.getX(c), BlockPos.getY(c), BlockPos.getZ(c));
			if (m.distToCenterSqr(px, py, pz) > r2) continue;
			mc.level.addParticle(GROUND, m.getX() + 0.5, m.getY() + 1.02, m.getZ() + 0.5, 0, 0, 0);
		}
		for (long c : view.sources()) {
			m.set(BlockPos.getX(c), BlockPos.getY(c), BlockPos.getZ(c));
			for (int i = 0; i < 4; i++) {
				mc.level.addAlwaysVisibleParticle(SOURCE, m.getX() + 0.5, m.getY() + 0.25 + i * 0.5, m.getZ() + 0.5, 0, 0, 0);
			}
		}
	}
}
