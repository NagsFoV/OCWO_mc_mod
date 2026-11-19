package io.github.nagsfov.ocwo.fluids;

import io.github.nagsfov.ocwo.util.OcwoProfiler;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelTicks;
import io.github.nagsfov.ocwo.fluids.layer.FluidLayer;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.LongPredicate;

/**
 * Fluid ticks of a level, with the OCWO water simulation. <b>Complete simulation</b> (Valentin, 15/11/2026): every
 * tick does all the water work due at that tick (flow tasks, equalization, pressure of every body of water that has
 * work, moisture every 10 ticks), nothing is left for later; if it is heavy, the game slows down. The amount of work
 * is set by the water simulation distance: beyond it, water is frozen (like chunks that do not tick).
 */
public class CustomFluidTicks extends LevelTicks<Fluid> {

	private final ArrayDeque<EqualizationFluidTask> equalizationQueue = new ArrayDeque<>();
	private final ObjectOpenHashSet<BlockPos> equalizationSet = new ObjectOpenHashSet<>(256, .5f);
	private final ServerLevel world;
	private final ObjectOpenHashSet<ChunkAccess> updatedFluidOverrides = new ObjectOpenHashSet<>(64, .5f);

	private final Long2IntOpenHashMap equalizationBlacklistSet = new Long2IntOpenHashMap(512, .5f);
	private final Int2ObjectOpenHashMap<LongOpenHashSet> equalizationBlacklistMap = new Int2ObjectOpenHashMap<>(16, .5f);

	private long taskCounter;
	private long blockReadCounter;
	private long blockUpdateCounter;

	private int tickNumber;

	private final io.github.nagsfov.ocwo.moisture.MoistureSimulation moisture;
	private final WaterBodies bodies;

	// Debug timing (exponential moving averages, ms), measured only while a player shows the F3 simulation lines
	private double debugFlowMs, debugPressureMs, debugMoistureMs;

	public double debugFlowMillis() {
		return debugFlowMs;
	}

	public double debugPressureMillis() {
		return debugPressureMs;
	}

	public double debugMoistureMillis() {
		return debugMoistureMs;
	}

	public double debugTotalMillis() {
		return debugFlowMs + debugPressureMs + debugMoistureMs;
	}

	/** Bodies of water with work at the end of the last tick (for the debug lines). */
	public int pressureQueueSize() {
		return bodies.workCount();
	}

	private static double ema(double old, long nanos) {
		return old * 0.9 + nanos / 1e6 * 0.1;
	}

	// ---- OCWO pressure (see WaterBodies and 06-pression.md) ----
	private static final int PRESSURE_SAMPLE_PERIOD = 20;
	private static final int PRESSURE_SAMPLES_PER_PLAYER = 16;
	private static final int PRESSURE_SAMPLE_RADIUS = 32;
	private boolean seeded;
	private static final long SLOW_TICK_NANOS = 1_000_000_000L;
	private long equalizationOps;
	/** Work left over at the end of the last tick (must stay 0: nothing is deferred), for the tests. */
	private int lastDeferred;

	/** Lets the flow ticks run only within the water simulation distance (water beyond it is frozen). */
	private static final class RangeGate implements LongPredicate {
		final LongPredicate base;
		CustomFluidTicks owner;

		RangeGate(LongPredicate base) {
			this.base = base;
		}

		@Override
		public boolean test(long chunkPos) {
			return base.test(chunkPos) && (owner == null || owner.bodies.inRangeChunk(chunkPos));
		}
	}

	public CustomFluidTicks(LongPredicate chunkPredicate, ServerLevel world) {
		this(new RangeGate(chunkPredicate), world);
	}

	private CustomFluidTicks(RangeGate gate, ServerLevel world) {
		super(gate);
		this.world = world;
		this.moisture = new io.github.nagsfov.ocwo.moisture.MoistureSimulation(world);
		this.bodies = new WaterBodies(world);
		gate.owner = this;
	}

	public static CustomFluidTicks get(LevelAccessor world) {
		return (CustomFluidTicks) world.getFluidTicks();
	}

	public io.github.nagsfov.ocwo.moisture.MoistureSimulation moisture() {
		return moisture;
	}

	public WaterBodies bodies() {
		return bodies;
	}

	/** Chunks that hold awake water (see WaterRest), in order. */
	private final it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet awakeChunks = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();

	public it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet awakeChunks() {
		return awakeChunks;
	}

	/** Awake blocks put back at rest by the last sweep (diagnostics). */
	public int lastSlept;

	public int lastDeferred() {
		return lastDeferred;
	}

	private boolean pressureEnabled() {
		return OcwoFluidGameRules.WATER_PRESSURE == null || world.getGameRules().get(OcwoFluidGameRules.WATER_PRESSURE);
	}

	public void fluidLayerUpdate(ChunkAccess chunk, BlockPos pos) {
		updatedFluidOverrides.add(chunk);
	}

	public void countTask() {
		taskCounter++;
	}

	public void countBlockRead() {
		blockReadCounter++;
	}

	public boolean allowedForEqualization(BlockPos pos) {
		return !equalizationBlacklistSet.containsKey(pos.asLong());
	}

	/** Water moved at {@code pos}: its body of water gets work (found or built at the next pressure pass). */
	public void enqueuePressure(BlockPos pos, FlowingFluid fluid) {
		if (pressureEnabled()) bodies.trigger(pos);
	}

	/** A block changed: the bodies of water around it are updated locally. */
	public void onBlockChanged(BlockPos pos) {
		bodies.onChange(pos);
	}

	/** Chunks generated in the last ticks -> game time of their birth (see WaterRest.isNewborn). */
	private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap newbornChunks = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();

	public it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap newbornChunks() {
		return newbornChunks;
	}

	/**
	 * Slow pressure trigger: stable but unbalanced bodies of fluid get no tick by themselves.
	 * Every second, a few random fluid blocks near each player are checked (their body is built if unknown).
	 */
	private void samplePressure() {
		if (world.getGameTime() % PRESSURE_SAMPLE_PERIOD != 0) return;
		var random = io.github.nagsfov.ocwo.util.FastMath.RANDOM;
		for (var player : world.players()) {
			BlockPos center = player.blockPosition();
			for (int i = 0; i < PRESSURE_SAMPLES_PER_PLAYER; i++) {
				BlockPos p = center.offset(
						random.nextInt(PRESSURE_SAMPLE_RADIUS * 2 + 1) - PRESSURE_SAMPLE_RADIUS,
						random.nextInt(PRESSURE_SAMPLE_RADIUS + 1) - PRESSURE_SAMPLE_RADIUS / 2,
						random.nextInt(PRESSURE_SAMPLE_RADIUS * 2 + 1) - PRESSURE_SAMPLE_RADIUS);
				if (!world.isLoaded(p)) continue;
				var fs = world.getFluidState(p);
				if (fs.getType() instanceof FlowingFluid ff && PressureSearch.isStillFull(fs, ff) && !WaterRest.isInert(world, p)) bodies.trigger(p);
			}
		}
	}

	/** Diagnostics for /ocwo pressure queue. */
	public List<String> pressureReport(int largest) {
		List<String> out = new java.util.ArrayList<>();
		out.add(String.format(java.util.Locale.ROOT, "masses d'eau suivies : %d (%d colonnes), %d avec du travail ; distance de simulation de l'eau : %d chunks",
				bodies.bodyCount(), bodies.columnCount(), bodies.workCount(), bodies.distance()));
		out.add(String.format(java.util.Locale.ROOT, "dernier tick : %d masses traitées, %d niveaux déplacés, %d blocs modifiés pris en compte, %d colonnes créées, %d séparations ; travail reporté : %d",
				bodies.lastBodiesWorked, bodies.lastTransfers, bodies.lastChanges, bodies.lastColumnsBuilt, bodies.lastSplits, lastDeferred));
		for (int[] b : bodies.largest(largest)) {
			out.add(String.format(java.util.Locale.ROOT, "  masse n°%d : %d colonnes%s", b[0], b[1], b[2] == 1 ? ", en cours d'équilibrage" : ""));
		}
		return out;
	}

	public String debugPressureQueue() {
		return String.join("; ", pressureReport(4));
	}

	public void onBlockUpdate(BlockPos pos, int tickDelay) {
		blockUpdateCounter++;
		tickDelay = FluidUtils.modifyTickRate(tickDelay);
		tickDelay--;
		if (tickDelay < 1) return;
		long p = pos.asLong();
		int t = tickNumber + tickDelay;
		int oldT = equalizationBlacklistSet.getOrDefault(p, -1);
		if (oldT < t) {
			equalizationBlacklistSet.put(p, t);
			var set = equalizationBlacklistMap.computeIfAbsent(t, ignored -> new LongOpenHashSet(256, .5f));
			set.add(p);
			if (oldT >= 0) {
				set = equalizationBlacklistMap.get(oldT);
				Objects.requireNonNull(set);
				set.remove(p);
			}
		}
	}

	public void done(BlockPos pos) {
		equalizationSet.remove(pos);
	}

	public void scheduleEqualization(BlockPos pos, FlowingFluid fluid, ServerLevel world) {
		if (equalizationSet.add(pos)) {
			equalizationQueue.add(new EqualizationFluidTask(pos, fluid, world));
		}
	}

	@Override
	public void tick(long time, int limit, BiConsumer<BlockPos, Fluid> biConsumer) {
		long profileStart = OcwoProfiler.isProfiling(world) ? System.nanoTime() : 0L;
		boolean debug = io.github.nagsfov.ocwo.debug.OcwoDebug.isActive();
		long d0 = debug ? System.nanoTime() : 0L;
		if (!seeded) {
			seeded = true;
			if (world.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
				io.github.nagsfov.ocwo.util.FastMath.RANDOM.setSeed(world.getSeed());
			}
		}
		long tickStart = System.nanoTime();
		taskCounter = 0;
		blockReadCounter = 0;
		blockUpdateCounter = 0;
		// every flow task due at this tick (no limit), except for water at rest
		final boolean rest = WaterRest.enabled(world);
		WaterRest.simulating(true);
		long s0 = profileStart != 0L ? System.nanoTime() : 0L;
		try {
			super.tick(time, Integer.MAX_VALUE, rest ? (pos, fluid) -> {
				if (!WaterRest.isResting(world, pos)) biConsumer.accept(pos, fluid);
			} : biConsumer);
		} finally {
			WaterRest.simulating(false);
		}
		if (s0 != 0L) OcwoProfiler.addSection(world, "tick: scheduled fluid ticks", System.nanoTime() - s0);
		ProfilerFiller profiler = Profiler.get();

		long d1 = debug ? System.nanoTime() : 0L;
		profiler.push("fluid pressure");
		long pressureStart = profileStart != 0L ? System.nanoTime() : 0L;
		if (pressureEnabled()) {
			samplePressure();
			// pressure transfers count as flow: natural water keeps its level while the reserve or its reservoir pays
			WaterRest.simulating(true);
			try {
				bodies.tick(world.getGameRules().get(OcwoFluidGameRules.PRESSURE_FLOW_MAX));
			} finally {
				WaterRest.simulating(false);
			}
			// changes made after this point (moisture, equalization) are the next tick's pressure work
			lastDeferred = bodies.deferred();
		} else {
			lastDeferred = 0;
			bodies.clearPending();
		}
		if (pressureStart != 0L) {
			OcwoProfiler.addPressureNanos(world, System.nanoTime() - pressureStart);
			OcwoProfiler.addSection(world, "tick: pressure", System.nanoTime() - pressureStart);
		}
		long m0 = profileStart != 0L ? System.nanoTime() : 0L;
		long d2 = debug ? System.nanoTime() : 0L;
		profiler.popPush("block moisture");
		moisture.tick();
		// sponges removed without dropping an item give their water back (see SpongeLogic.onSpongeGone)
		io.github.nagsfov.ocwo.sponge.SpongeLogic.tick(world);
		if (m0 != 0L) OcwoProfiler.addSection(world, "tick: moisture and sponges", System.nanoTime() - m0);
		long e0 = profileStart != 0L ? System.nanoTime() : 0L;
		long d3 = debug ? System.nanoTime() : 0L;
		profiler.popPush("fluid equalization");
		// every equalization task queued before this pass (the ones they queue are for the next tick)
		long equalizationTaskCounter = 0;
		WaterRest.simulating(true);
		try {
			for (int n = equalizationQueue.size(); n > 0; n--) {
				EqualizationFluidTask task = equalizationQueue.pollFirst();
				if (task == null) break;
				if (equalizationSet.remove(task.pos)) {
					if (!equalizationBlacklistSet.containsKey(task.pos.asLong())) {
						task.run();
						equalizationTaskCounter++;
					}
				}
			}
		} finally {
			WaterRest.simulating(false);
		}
		if (e0 != 0L) OcwoProfiler.addSection(world, "tick: equalization", System.nanoTime() - e0);
		// awake water that has stopped moving goes back to rest
		long r0 = profileStart != 0L ? System.nanoTime() : 0L;
		int slept = WaterRest.sleepSweep(world);
		if (r0 != 0L) OcwoProfiler.addSection(world, "tick: back to rest", System.nanoTime() - r0);
		if (world.getGameTime() % 20 == 0) lastSlept = slept;
		if (FluidUtils.FLUID_CHUNK_LAYER && !updatedFluidOverrides.isEmpty()) {
			profiler.popPush("updating fluid chunks");
			for (ChunkAccess ca : updatedFluidOverrides) {
				FluidLayer.update(ca);
			}
			updatedFluidOverrides.clear();
		}
		profiler.pop();

		var set = equalizationBlacklistMap.remove(tickNumber);
		if (set != null) {
			LongIterator itr = set.iterator();
			while (itr.hasNext()) {
				equalizationBlacklistSet.remove(itr.nextLong());
			}
		}
		tickNumber++;
		equalizationOps = equalizationTaskCounter;
		long spent = System.nanoTime() - tickStart;
		if (spent > SLOW_TICK_NANOS) {
			// the tick is never cut: just say why it was slow
			io.github.nagsfov.ocwo.Ocwo.LOGGER.warn(
					"Water simulation took {} ms this tick in {} ({} flow tasks, {} equalization tasks, {} bodies of water, {} levels moved by pressure, {} columns built). "
							+ "Lower the game rule ocwo:water_simulation_distance if this happens often.",
					spent / 1_000_000L, world.dimension().identifier(), taskCounter, equalizationOps, bodies.lastBodiesWorked,
					bodies.lastTransfers, bodies.lastColumnsBuilt);
		}
		if (profileStart != 0L) {
			OcwoProfiler.endTick(world, System.nanoTime() - profileStart);
		}
		if (debug) {
			long d4 = System.nanoTime();
			debugFlowMs = ema(debugFlowMs, (d1 - d0) + (d4 - d3));
			debugPressureMs = ema(debugPressureMs, d2 - d1);
			debugMoistureMs = ema(debugMoistureMs, d3 - d2);
		}
		io.github.nagsfov.ocwo.debug.OcwoDebug.tick(world);
	}
}
