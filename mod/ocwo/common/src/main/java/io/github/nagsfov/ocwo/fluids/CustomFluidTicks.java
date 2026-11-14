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
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.LongPredicate;

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

	// Debug panel timing (exponential moving averages, ms), measured only while a player has /ocwo debug on
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

	public int pressureQueueSize() {
		return pressureQueue.size() + pressureDelayed.values().stream().mapToInt(java.util.List::size).sum();
	}

	private static double ema(double old, long nanos) {
		return old * 0.9 + nanos / 1e6 * 0.1;
	}

	// ---- OCWO pressure (see PressureSearch and 06-pression.md) ----
	private static final int PRESSURE_SAMPLE_PERIOD = 20;
	private static final int PRESSURE_SAMPLES_PER_PLAYER = 16;
	private static final int PRESSURE_SAMPLE_RADIUS = 32;
	/** A trigger blocked this many times in a row is dropped (with an increasing delay before). */
	private static final int PRESSURE_MAX_FAILS = 12;

	/** sampled: queued by the slow check near players (not by water that moved). */
	private record PressureEntry(BlockPos pos, FlowingFluid fluid, long notBefore, int fails, boolean sampled) {
	}

	/** Entries ready to run (processed from the head, as far as the budget allows). */
	private final ArrayDeque<PressureEntry> pressureQueue = new ArrayDeque<>();
	/** Entries waiting for a later tick, by game tick. */
	private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<java.util.ArrayList<PressureEntry>> pressureDelayed = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
	private final LongOpenHashSet pressureQueued = new LongOpenHashSet(256, .5f);
	/** Columns already explored this tick: other triggers of the same body wait for the next tick. */
	private final LongOpenHashSet pressureSearched = new LongOpenHashSet(1024, .5f);
	/** Levels carried this tick by each column of fluid (key = bottom block), see PressureSearch. */
	private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap pressureUsage = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
	/** Blocks of bodies found balanced recently: triggers inside them are dropped without a search. */
	private final LongOpenHashSet pressureBalanced = new LongOpenHashSet(1024, .5f);
	private static final int PRESSURE_BALANCED_MEMORY = 20;
	/** Max time spent in pressure steps per tick, in nanoseconds (3 ms). */
	private static final long PRESSURE_TIME_BUDGET_NANOS = 3_000_000L;

	public CustomFluidTicks(LongPredicate chunkPredicate, ServerLevel world) {
		super(chunkPredicate);
		this.world = world;
		this.moisture = new io.github.nagsfov.ocwo.moisture.MoistureSimulation(world);
	}

	public static CustomFluidTicks get(LevelAccessor world) {
		return (CustomFluidTicks) world.getFluidTicks();
	}

	public io.github.nagsfov.ocwo.moisture.MoistureSimulation moisture() {
		return moisture;
	}

	public int getTaskLimit() {
		return world.getGameRules().get(OcwoFluidGameRules.TASK_LIMIT);
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

	/** Queues a full, non-falling block of fluid as the start of a pressure step (deduplicated). */
	public void enqueuePressure(BlockPos pos, FlowingFluid fluid) {
		if (pressureQueued.add(pos.asLong())) {
			pressureQueue.add(new PressureEntry(pos.immutable(), fluid, 0, 0, false));
		}
		// water moved here: whatever was concluded about this body is outdated
		pressureBalanced.remove(pos.asLong());
	}

	/** Queued by the slow check: dropped without a search if its body was found balanced recently. */
	private void enqueueSampled(BlockPos pos, FlowingFluid fluid) {
		if (pressureQueued.add(pos.asLong())) {
			pressureQueue.add(new PressureEntry(pos.immutable(), fluid, 0, 0, true));
		}
	}

	/**
	 * Runs the queued pressure steps. A trigger that moved water runs again next tick (and so does the full block
	 * next to the low point it filled); a balanced one is dropped; a blocked one is retried with an increasing
	 * delay, then dropped.
	 */
	private void tickPressure() {
		pressureUsage.clear();
		pressureSearched.clear();
		long now = world.getGameTime();
		// Entries delayed to this tick become ready (appended after the ones left from previous ticks).
		java.util.ArrayList<PressureEntry> due = pressureDelayed.remove(now);
		if (due != null) pressureQueue.addAll(due);
		if (pressureQueue.isEmpty()) return;
		int limit = world.getGameRules().get(OcwoFluidGameRules.PRESSURE_SEARCH_LIMIT);
		if (limit <= 0) {
			pressureQueue.clear();
			pressureDelayed.clear();
			pressureQueued.clear();
			return;
		}
		if (now % PRESSURE_BALANCED_MEMORY == 0) pressureBalanced.clear();
		int budget = Math.max(1, getTaskLimit() / 100);
		int polls = budget * 8; // bounds the work spent skipping entries of balanced bodies
		long deadline = System.nanoTime() + PRESSURE_TIME_BUDGET_NANOS;
		BlockPos.MutableBlockPos lowSide = new BlockPos.MutableBlockPos();
		PressureEntry e;
		while (budget > 0 && polls-- > 0 && System.nanoTime() < deadline && (e = pressureQueue.poll()) != null) {
			if (e.sampled() && pressureBalanced.contains(e.pos().asLong())) {
				pressureQueued.remove(e.pos().asLong());
				continue;
			}
			if (!pressureSearched.isEmpty()) {
				long col = PressureSearch.columnKey(world, e.fluid(), e.pos());
				if (col != 0L && pressureSearched.contains(col)) {
					// its body was already handled this tick: next tick, without counting a failure
					delay(e.pos(), e.fluid(), now + 1, e.fails());
					continue;
				}
			}
			budget--;
			PressureSearch.Outcome outcome = PressureSearch.step(world, e.fluid(), e.pos(), pressureUsage, limit, lowSide,
					pressureBalanced, pressureSearched);
			OcwoProfiler.countPressureStep(world, outcome == PressureSearch.Outcome.MOVED);
			switch (outcome) {
				case MOVED -> {
					delay(e.pos(), e.fluid(), now + 1, 0);
					BlockPos low = lowSide.immutable();
					if (!low.equals(e.pos()) && pressureQueued.add(low.asLong())) {
						delay(low, e.fluid(), now + 1, 0);
					}
				}
				case BLOCKED -> {
					int fails = e.fails() + 1;
					if (fails > PRESSURE_MAX_FAILS) {
						pressureQueued.remove(e.pos().asLong());
					} else {
						delay(e.pos(), e.fluid(), now + Math.min(64L, 1L << fails), fails);
					}
				}
				case BALANCED -> pressureQueued.remove(e.pos().asLong());
			}
		}
	}


	private void delay(BlockPos pos, FlowingFluid fluid, long tick, int fails) {
		pressureDelayed.computeIfAbsent(tick, k -> new java.util.ArrayList<>()).add(new PressureEntry(pos, fluid, tick, fails, false));
	}

	/**
	 * Slow pressure trigger: stable but unbalanced bodies of fluid get no tick by themselves.
	 * Every second, a few random fluid blocks near each player are queued for a pressure step.
	 */
	private void samplePressure() {
		if (world.getGameTime() % PRESSURE_SAMPLE_PERIOD != 0) return;
		if (world.getGameRules().get(OcwoFluidGameRules.PRESSURE_SEARCH_LIMIT) <= 0) return;
		var random = world.getRandom();
		for (var player : world.players()) {
			BlockPos center = player.blockPosition();
			for (int i = 0; i < PRESSURE_SAMPLES_PER_PLAYER; i++) {
				BlockPos p = center.offset(
						random.nextInt(PRESSURE_SAMPLE_RADIUS * 2 + 1) - PRESSURE_SAMPLE_RADIUS,
						random.nextInt(PRESSURE_SAMPLE_RADIUS + 1) - PRESSURE_SAMPLE_RADIUS / 2,
						random.nextInt(PRESSURE_SAMPLE_RADIUS * 2 + 1) - PRESSURE_SAMPLE_RADIUS);
				if (!world.isLoaded(p)) continue;
				var fs = world.getFluidState(p);
				if (fs.getType() instanceof FlowingFluid ff && PressureSearch.isStillFull(fs, ff)) {
					enqueueSampled(p, ff);
				}
			}
		}
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
		taskCounter = 0;
		blockReadCounter = 0;
		blockUpdateCounter = 0;
		super.tick(time, getTaskLimit(), biConsumer);
		ProfilerFiller profiler = Profiler.get();

		int eqLim = getTaskLimit() / 100;
		if (eqLim < 1) eqLim = 1;
		long equalizationTaskCounter = 0;
		//if (taskCounter > 0)
		//	System.out.printf("t:%s \te:%s \tr:%s \tu:%s\n", taskCounter, equalizationSet.size(), blockReadCounter, blockUpdateCounter);

		long d1 = debug ? System.nanoTime() : 0L;
		profiler.push("fluid pressure");
		long pressureStart = profileStart != 0L ? System.nanoTime() : 0L;
		samplePressure();
		tickPressure();
		if (pressureStart != 0L) OcwoProfiler.addPressureNanos(world, System.nanoTime() - pressureStart);
		long d2 = debug ? System.nanoTime() : 0L;
		profiler.popPush("block moisture");
		moisture.tick();
		long d3 = debug ? System.nanoTime() : 0L;
		profiler.popPush("fluid equalization");
		EqualizationFluidTask task;
		EqualizationFluidTask bt = equalizationQueue.peekLast();
		while (equalizationTaskCounter < eqLim && (task = equalizationQueue.pollFirst()) != null) {
			if (bt == task) {
				bt = null;
			}
			if (equalizationSet.remove(task.pos)) {
				if (!equalizationBlacklistSet.containsKey(task.pos.asLong())) {
					task.run();
					equalizationTaskCounter++;
				}
			}
		}
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
		if (bt == null) {
			tickNumber++;
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
