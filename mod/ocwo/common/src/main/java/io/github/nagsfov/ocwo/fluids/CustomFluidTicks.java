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
		return pressureQueue.size() + pressureOwners.size() + pressureDelayed.values().stream().mapToInt(java.util.List::size).sum();
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
	/**
	 * owner: the trigger that carries on after a search (moved: next tick; blocked: retried later); other entries
	 * (events, samples) are dropped when a search of their body started after them. seq: order of creation.
	 */
	private record PressureEntry(BlockPos pos, FlowingFluid fluid, long seq, int fails, boolean sampled, boolean owner, long created) {
	}

	/** Entries ready to run (processed from the head, as far as the budget allows). */
	private final ArrayDeque<PressureEntry> pressureQueue = new ArrayDeque<>();
	/** Triggers that carry on the balancing of a body (moved: next tick; blocked: later), served first. */
	private final ArrayDeque<PressureEntry> pressureOwners = new ArrayDeque<>();
	/** Columns of bodies too large to explore where nothing was found to move (cleared now and then). */
	private final LongOpenHashSet pressureUnresolved = new LongOpenHashSet(1024, .5f);
	private static final int PRESSURE_UNRESOLVED_MEMORY = 200;
	private final BlockPos.MutableBlockPos pressureLowSide = new BlockPos.MutableBlockPos();
	private final int[] pressureMoved = new int[3];
	/** Outcomes (MOVED, BALANCED, BLOCKED, UNRESOLVED) and skipped entries, current and last window (diagnostics). */
	private static final int STAT_SKIPPED = 4;
	private static final int PRESSURE_STATS_WINDOW = 200;
	private final long[] pressureStats = new long[5];
	private final long[] pressureStatsLast = new long[5];
	/** Entries waiting for a later tick, by game tick. */
	private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<java.util.ArrayList<PressureEntry>> pressureDelayed = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
	private final LongOpenHashSet pressureQueued = new LongOpenHashSet(256, .5f);
	/** Columns already explored this tick: other triggers of the same body wait for the next tick. */
	private final LongOpenHashSet pressureSearched = new LongOpenHashSet(1024, .5f);
	/** Columns explored by the last search of the current search, see tickPressure. */
	private final LongOpenHashSet pressureLastSearch = new LongOpenHashSet(1024, .5f);
	/** Sequence number of the last search that explored each column (cleared now and then). */
	private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap pressureSearchSeq = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
	private long pressureSeq;
	private static final int PRESSURE_SEARCH_MEMORY = 200;
	/** Levels carried this tick by each column of fluid (key = bottom block), see PressureSearch. */
	private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap pressureUsage = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
	/** Blocks of bodies found balanced recently: triggers inside them are dropped without a search. */
	private final LongOpenHashSet pressureBalanced = new LongOpenHashSet(1024, .5f);
	private static final int PRESSURE_BALANCED_MEMORY = 100;
	private boolean seeded;
	/** System.nanoTime() after which the optional time budget stops this tick's work (Long.MAX_VALUE = no budget). */
	private long deadline = Long.MAX_VALUE;

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
		return scaled(world.getGameRules().get(OcwoFluidGameRules.TASK_LIMIT));
	}

	/** A quota multiplied by the game rule {@code water_simulation_scale} (at least 1). */
	public int scaled(int quota) {
		long v = (long) quota * world.getGameRules().get(OcwoFluidGameRules.SIMULATION_SCALE) / 100L;
		return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, v));
	}

	/** Operations of this tick, for the slow tick warning. */
	private long pressureOps;
	private long equalizationOps;
	private static final long SLOW_TICK_NANOS = 1_000_000_000L;

	/**
	 * True when the optional game rule {@code water_time_budget_ms} is on and this tick has used it up. Off by default:
	 * the simulation is deterministic and the tick always finishes its quotas (the game slows down, not the water).
	 */
	public boolean outOfTime() {
		return deadline != Long.MAX_VALUE && System.nanoTime() > deadline;
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
			pressureQueue.add(new PressureEntry(pos.immutable(), fluid, ++pressureSeq, 0, false, false, world.getGameTime()));
		}
		// water moved here: whatever was concluded about this body is outdated
		pressureBalanced.remove(pos.asLong());
	}

	/** Queued by the slow check: dropped without a search if its body was found balanced recently. */
	private void enqueueSampled(BlockPos pos, FlowingFluid fluid) {
		if (pressureQueued.add(pos.asLong())) {
			pressureQueue.add(new PressureEntry(pos.immutable(), fluid, ++pressureSeq, 0, true, false, world.getGameTime()));
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
		if (now % PRESSURE_STATS_WINDOW == 0) {
			System.arraycopy(pressureStats, 0, pressureStatsLast, 0, pressureStats.length);
			java.util.Arrays.fill(pressureStats, 0);
		}
		// Entries delayed to this tick become ready, after the ones left from previous ticks.
		java.util.ArrayList<PressureEntry> due = pressureDelayed.remove(now);
		if (due != null) for (PressureEntry e : due) (e.owner() ? pressureOwners : pressureQueue).add(e);
		if (pressureQueue.isEmpty() && pressureOwners.isEmpty()) return;
		int limit = world.getGameRules().get(OcwoFluidGameRules.PRESSURE_SEARCH_LIMIT);
		if (limit <= 0) {
			pressureQueue.clear();
			pressureOwners.clear();
			pressureDelayed.clear();
			pressureQueued.clear();
			return;
		}
		if (now % PRESSURE_BALANCED_MEMORY == 0) pressureBalanced.clear();
		if (now % PRESSURE_SEARCH_MEMORY == 0) pressureSearchSeq.clear();
		if (now % PRESSURE_UNRESOLVED_MEMORY == 0) pressureUnresolved.clear();
		// Fixed quota of operations: 1 per entry taken from a queue, 1 per block of water explored by a search,
		// 1 per level moved (the tick waits for it). Bodies already being balanced (owners) go first, but a quarter
		// of the quota is kept for new triggers (events, samples), then the owners take what is left.
		int budget = scaled(world.getGameRules().get(OcwoFluidGameRules.PRESSURE_OPERATIONS_PER_TICK));
		int startBudget = budget;
		int reserve = startBudget / 4;
		PressureEntry e;
		while (budget > reserve && !outOfTime() && (e = pressureOwners.poll()) != null) budget = process(e, budget, limit, now);
		while (budget > 0 && !outOfTime() && (e = pressureQueue.poll()) != null) budget = process(e, budget, limit, now);
		while (budget > 0 && !outOfTime() && (e = pressureOwners.poll()) != null) budget = process(e, budget, limit, now);
		pressureOps = startBudget - budget;
	}

	/** One entry of the pressure queues; returns the quota left. */
	private int process(PressureEntry e, int budget, int limit, long now) {
		budget--;
		long pos = e.pos().asLong();
		if (e.sampled() && pressureBalanced.contains(pos)) {
			pressureQueued.remove(pos);
			pressureStats[STAT_SKIPPED]++;
			return budget;
		}
		// One search per body and per tick, and no search for an event that a later search already covered: a
		// big body that keeps receiving water must not take the quota of the others (they wait in turn).
		long col = PressureSearch.columnKey(world, e.fluid(), e.pos());
		if (col != 0L) {
			if (!e.owner() && pressureUnresolved.contains(col)) {
				// part of a body too large to be explored (a lake, the sea) where nothing was found to move:
				// pressure has nothing to do there for a while (the normal flow still runs)
				pressureQueued.remove(pos);
				pressureStats[STAT_SKIPPED]++;
				return budget;
			}
			boolean coveredByLaterSearch = pressureSearchSeq.getOrDefault(col, -1L) > e.seq();
			if (pressureSearched.contains(col)) {
				if (e.owner() || coveredByLaterSearch) {
					pressureQueued.remove(pos); // that search's own trigger carries on
					pressureStats[STAT_SKIPPED]++;
				} else {
					delayEntry(e, now + 1); // water moved here after this tick's search: next tick
				}
				return budget;
			}
			if (!e.owner() && coveredByLaterSearch) {
				pressureQueued.remove(pos);
				pressureStats[STAT_SKIPPED]++;
				return budget;
			}
		}
		pressureLastSearch.clear();
		int flowMax = world.getGameRules().get(OcwoFluidGameRules.PRESSURE_FLOW_MAX);
		PressureSearch.Outcome outcome = PressureSearch.step(world, e.fluid(), e.pos(), pressureUsage, limit, pressureLowSide,
				pressureBalanced, pressureLastSearch, Math.max(1, budget), flowMax, pressureMoved);
		budget -= pressureMoved[0] + pressureMoved[1];
		long searchSeq = ++pressureSeq;
		for (it.unimi.dsi.fastutil.longs.LongIterator it = pressureLastSearch.iterator(); it.hasNext(); ) {
			long k = it.nextLong();
			pressureSearched.add(k);
			pressureSearchSeq.put(k, searchSeq);
			if (outcome == PressureSearch.Outcome.UNRESOLVED) pressureUnresolved.add(k);
		}
		OcwoProfiler.countPressureStep(world, outcome == PressureSearch.Outcome.MOVED);
		pressureStats[outcome.ordinal()]++;
		switch (outcome) {
			case MOVED -> {
				if (pressureMoved[2] == 1) {
					delay(e.pos(), e.fluid(), now + 1, 0);
				} else {
					// part of a body too large to be explored (a lake being filled): it carries on, but in turn with
					// the new triggers, not ahead of the bodies that can be balanced
					delayEntry(new PressureEntry(e.pos(), e.fluid(), ++pressureSeq, 0, false, false, now), now + 1);
				}
				BlockPos low = pressureLowSide.immutable();
				if (!low.equals(e.pos()) && pressureQueued.add(low.asLong())) {
					if (pressureMoved[2] == 1) delay(low, e.fluid(), now + 1, 0);
					else delayEntry(new PressureEntry(low, e.fluid(), ++pressureSeq, 0, false, false, now), now + 1);
				}
			}
			case BLOCKED -> {
				int fails = e.fails() + 1;
				if (fails > PRESSURE_MAX_FAILS) {
					pressureQueued.remove(pos);
				} else {
					delay(e.pos(), e.fluid(), now + Math.min(64L, 1L << fails), fails);
				}
			}
			case BALANCED, UNRESOLVED -> pressureQueued.remove(pos);
		}
		return budget;
	}

	/** Pressure entries (ready or delayed) inside a box (tests). */
	public int pressureEntriesIn(BlockPos a, BlockPos b) {
		net.minecraft.world.level.levelgen.structure.BoundingBox box = net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(a, b);
		int n = 0;
		for (PressureEntry e : pressureQueue) if (box.isInside(e.pos())) n++;
		for (PressureEntry e : pressureOwners) if (box.isInside(e.pos())) n++;
		for (var list : pressureDelayed.values()) for (PressureEntry e : list) if (box.isInside(e.pos())) n++;
		return n;
	}

	/** Debug summary of the pressure queue (tests). */
	public String debugPressureQueue() {
		return String.join("; ", pressureReport(4));
	}

	/**
	 * Summary of the pressure queues for /ocwo pressure queue and the tests: entries by kind, outcomes over the last
	 * window, and the oldest entries with the size of their body.
	 */
	public java.util.List<String> pressureReport(int oldest) {
		long now = world.getGameTime();
		int owners = pressureOwners.size(), events = 0, samples = 0, delayed = 0;
		for (PressureEntry e : pressureQueue) { if (e.sampled()) samples++; else events++; }
		java.util.List<PressureEntry> all = new java.util.ArrayList<>(pressureOwners);
		all.addAll(pressureQueue);
		for (var list : pressureDelayed.values()) { delayed += list.size(); all.addAll(list); }
		java.util.List<String> out = new java.util.ArrayList<>();
		out.add(String.format(java.util.Locale.ROOT, "file de pression : %d prêts (%d suites de masses, %d mouvements d'eau, %d vérifications lentes), %d programmés plus tard",
				owners + events + samples, owners, events, samples, delayed));
		long[] st = now % PRESSURE_STATS_WINDOW < PRESSURE_STATS_WINDOW / 2 ? pressureStatsLast : pressureStats;
		out.add(String.format(java.util.Locale.ROOT, "%d derniers ticks : %d recherches avec eau déplacée, %d équilibrées, %d bloquées, %d trop grandes sans rien à déplacer ; %d entrées écartées sans recherche",
				PRESSURE_STATS_WINDOW, st[0], st[1], st[2], st[3], st[STAT_SKIPPED]));
		out.add(String.format(java.util.Locale.ROOT, "quota : %d opérations par tick, %d utilisées au dernier tick ; mémoire : %d blocs équilibrés, %d colonnes trop grandes",
				scaled(world.getGameRules().get(OcwoFluidGameRules.PRESSURE_OPERATIONS_PER_TICK)), pressureOps, pressureBalanced.size(), pressureUnresolved.size()));
		all.sort(java.util.Comparator.comparingLong(PressureEntry::created));
		int limit = Math.max(1, world.getGameRules().get(OcwoFluidGameRules.PRESSURE_SEARCH_LIMIT));
		for (int k = 0; k < Math.min(oldest, all.size()); k++) {
			PressureEntry e = all.get(k);
			PressureSearch.Inspection in = PressureSearch.inspect(world, e.pos(), limit);
			String kind = e.owner() ? "suite" : e.sampled() ? "vérification" : "mouvement";
			out.add(String.format(java.util.Locale.ROOT, "  %s (%s, %d ticks, %d échecs) : masse de %s%d colonnes, %s", e.pos().toShortString(), kind,
					now - e.created(), e.fails(), in.complete() ? "" : "plus de ", in.columns(),
					!in.complete() ? "trop grande" : in.balanced() ? "équilibrée" : "à équilibrer"));
		}
		return out;
	}

	/** Queues the trigger that carries on after a search (owner), for a later tick. */
	private void delay(BlockPos pos, FlowingFluid fluid, long tick, int fails) {
		delayEntry(new PressureEntry(pos, fluid, ++pressureSeq, fails, false, true, world.getGameTime()), tick);
	}

	private void delayEntry(PressureEntry e, long tick) {
		pressureDelayed.computeIfAbsent(tick, k -> new java.util.ArrayList<>()).add(e);
	}

	/**
	 * Slow pressure trigger: stable but unbalanced bodies of fluid get no tick by themselves.
	 * Every second, a few random fluid blocks near each player are queued for a pressure step.
	 */
	private void samplePressure() {
		if (world.getGameTime() % PRESSURE_SAMPLE_PERIOD != 0) return;
		if (world.getGameRules().get(OcwoFluidGameRules.PRESSURE_SEARCH_LIMIT) <= 0) return;
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
		if (!seeded) {
			seeded = true;
			if (world.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
				io.github.nagsfov.ocwo.util.FastMath.RANDOM.setSeed(world.getSeed());
			}
		}
		long tickStart = System.nanoTime();
		pressureOps = 0;
		int budgetMs = world.getGameRules().get(OcwoFluidGameRules.WATER_TIME_BUDGET_MS);
		deadline = budgetMs > 0 ? System.nanoTime() + budgetMs * 1_000_000L : Long.MAX_VALUE;
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
		while (equalizationTaskCounter < eqLim && !outOfTime() && (task = equalizationQueue.pollFirst()) != null) {
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
		equalizationOps = equalizationTaskCounter;
		long spent = System.nanoTime() - tickStart;
		if (spent > SLOW_TICK_NANOS) {
			// the tick is never cut (unless water_time_budget_ms is set): just say why it was slow
			io.github.nagsfov.ocwo.Ocwo.LOGGER.warn(
					"Water simulation took {} ms this tick in {} ({} flow tasks, {} equalization tasks, {} pressure operations). "
							+ "Lower the game rule ocwo:water_simulation_scale if this happens often.",
					spent / 1_000_000L, world.dimension().identifier(), taskCounter, equalizationOps, pressureOps);
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
