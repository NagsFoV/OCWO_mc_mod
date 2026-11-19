package io.github.nagsfov.ocwo.debug;

import com.mojang.brigadier.CommandDispatcher;
import io.github.nagsfov.ocwo.fluids.CustomFluidTicks;
import io.github.nagsfov.ocwo.fluids.FluidUtils;
import io.github.nagsfov.ocwo.fluids.OcwoFluidGameRules;
import io.github.nagsfov.ocwo.fluids.PressureSearch;
import io.github.nagsfov.ocwo.fluids.WaterBodies;
import io.github.nagsfov.ocwo.fluids.layer.FluidLayer;
import io.github.nagsfov.ocwo.moisture.Moisture;
import io.github.nagsfov.ocwo.moisture.MoistureData;
import io.github.nagsfov.ocwo.moisture.MoistureProps;
import io.github.nagsfov.ocwo.moisture.MoistureSimulation;
import io.github.nagsfov.ocwo.platform.Services;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.nagsfov.ocwo.debug.DebugPayload.key;
import static io.github.nagsfov.ocwo.debug.DebugPayload.line;

/**
 * OCWO lines of the F3 debug screen (water at head, block looked at, simulation). The client says which OCWO
 * entries it shows ({@link DebugSubscribePayload}, only while F3 shows at least one of them); the server computes
 * them every {@link #PERIOD} ticks for those players only (pressure and moisture only exist on the server).
 * Nothing is sent when the reducedDebugInfo game rule is on; the simulation group is for operators (and for the
 * owner of a single player world).
 */
public final class OcwoDebug {

	private static final Map<UUID, Integer> SUBSCRIBED = new ConcurrentHashMap<>();
	private static final int PERIOD = 10;
	private static final double REACH = 6.0;
	private static volatile boolean active;

	private OcwoDebug() {
	}

	/** True while a player shows the simulation group: the water code then measures its own time. */
	public static boolean isActive() {
		return active;
	}

	/**
	 * {@code /ocwo debug} only says where the debug information went; {@code /ocwo pressure queue} (operators) sums up
	 * the pressure queue of the current dimension.
	 */
	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("ocwo")
				.then(Commands.literal("debug")
						.executes(ctx -> {
							ctx.getSource().sendSuccess(() -> Component.translatable("ocwo.debug.command"), false);
							return 1;
						}))
				.then(Commands.literal("water")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.literal("rest").then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 32))
								.executes(ctx -> {
									int r = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "radius");
									int n = io.github.nagsfov.ocwo.fluids.WaterRest.restAround(ctx.getSource().getLevel(), BlockPos.containing(ctx.getSource().getPosition()), r);
									ctx.getSource().sendSuccess(() -> Component.translatable("ocwo.water.rest", n, r), true);
									return n;
								})))
						.then(Commands.literal("info").then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 32))
								.executes(ctx -> {
									int r = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "radius");
									BlockPos at = BlockPos.containing(ctx.getSource().getPosition());
									int n = io.github.nagsfov.ocwo.fluids.WaterRest.awakeAround(ctx.getSource().getLevel(), at, r);
									var ledger = io.github.nagsfov.ocwo.fluids.WaterReserves.get(ctx.getSource().getLevel());
									long reserve = ledger.reserve(ctx.getSource().getLevel(), at);
									String clouds = levels(ledger.clouds(at));
									ctx.getSource().sendSuccess(() -> Component.translatable("ocwo.water.info", n, r, reserve, clouds), false);
									return n;
								})))
							.then(Commands.literal("ledger")
									.executes(ctx -> ledger(ctx.getSource()))))
				.then(Commands.literal("pressure")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.literal("queue")
								.executes(ctx -> {
									List<String> lines = CustomFluidTicks.get(ctx.getSource().getLevel()).pressureReport(5);
									for (String l : lines) ctx.getSource().sendSuccess(() -> Component.literal(l), false);
									return lines.size();
								}))));
	}

	/** Units as water levels, one decimal. */
	private static String levels(long units) {
		return String.format(Locale.ROOT, "%.1f", units / (double) Moisture.UNITS_PER_LEVEL);
	}

	/**
	 * {@code /ocwo water ledger}: what the zone (4 x 4 chunks) of the source holds, counted in its loaded chunks:
	 * visible water, block moisture, reserve, clouds, and the total in units (03, "Registre d'eau par zone").
	 */
	private static int ledger(CommandSourceStack source) {
		ServerLevel level = source.getLevel();
		BlockPos at = BlockPos.containing(source.getPosition());
		var count = io.github.nagsfov.ocwo.fluids.WaterReserves.get(level).count(level, at);
		long zone = count.zone();
		int zx = io.github.nagsfov.ocwo.fluids.WaterReserves.zoneX(zone), zz = io.github.nagsfov.ocwo.fluids.WaterReserves.zoneZ(zone);
		source.sendSuccess(() -> Component.translatable("ocwo.water.ledger.zone", zx, zz, zx << 6, zz << 6, (zx << 6) + 63, (zz << 6) + 63,
				count.loadedChunks()), false);
		source.sendSuccess(() -> Component.translatable("ocwo.water.ledger.stocks", levels(count.liquidUnits()), levels(count.moistureUnits()),
				levels(count.reserveUnits()), levels(count.cloudUnits())), false);
		source.sendSuccess(() -> Component.translatable("ocwo.water.ledger.total", count.total(), levels(count.total())), false);
		var sources = io.github.nagsfov.ocwo.fluids.WaterReserves.get(level).sourcesOf(zone);
		StringBuilder list = new StringBuilder();
		for (int i = 0; i < Math.min(5, sources.size()); i++) list.append(i == 0 ? "" : "; ").append(BlockPos.of(sources.getLong(i)).toShortString());
		source.sendSuccess(() -> Component.translatable("ocwo.water.ledger.sources", sources.size(), list.toString()), false);
		return (int) Math.min(Integer.MAX_VALUE, count.total() / Moisture.UNITS_PER_LEVEL);
	}

	/** A player's client changed the OCWO entries shown in F3 (0 = none). */
	public static void onSubscribe(ServerPlayer player, int mask) {
		if (mask == 0) SUBSCRIBED.remove(player.getUUID());
		else SUBSCRIBED.put(player.getUUID(), mask);
		refreshActive();
	}

	public static void onPlayerLeft(UUID id) {
		SUBSCRIBED.remove(id);
		refreshActive();
	}

	private static void refreshActive() {
		boolean a = false;
		for (int m : SUBSCRIBED.values()) a |= (m & DebugSubscribePayload.SIM) != 0;
		active = a;
	}

	/** Number of players currently receiving OCWO debug lines (tests). */
	public static int subscribers() {
		return SUBSCRIBED.size();
	}

	private static boolean mayUseSimulation(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (!server.isDedicatedServer() && server.isSingleplayerOwner(player.nameAndId())) return true;
		return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(player.createCommandSourceStack());
	}

	/** Called every tick for each level, at the end of its fluid tick. */
	public static void tick(ServerLevel level) {
		if (SUBSCRIBED.isEmpty() || level.getGameTime() % PERIOD != 0) return;
		if (level.getGameRules().get(GameRules.REDUCED_DEBUG_INFO)) return;
		for (ServerPlayer player : level.players()) {
			Integer mask = SUBSCRIBED.get(player.getUUID());
			if (mask == null) continue;
			Services.PLATFORM.sendDebug(player, build(level, player, mask));
		}
	}

	/** The lines for these groups (public for the tests). */
	public static DebugPayload build(ServerLevel level, ServerPlayer player, int mask) {
		List<String> head = new ArrayList<>(), block = new ArrayList<>(), sim = new ArrayList<>();
		if ((mask & DebugSubscribePayload.HEAD) != 0) headWater(level, player, head);
		if ((mask & DebugSubscribePayload.BLOCK) != 0) lookedAt(level, player, block);
		if ((mask & DebugSubscribePayload.SIM) != 0) {
			if (mayUseSimulation(player)) simulation(level, sim);
			else sim.add(line("ocwo.debug.sim.operators"));
		}
		return new DebugPayload(head, block, sim);
	}

	// ------------------------------------------------------------------ content

	private static String fluidName(FluidState fs) {
		return BuiltInRegistries.FLUID.getKey(fs.getType()).getPath().replace("flowing_", "");
	}

	private static boolean inFluidLayer(ServerLevel level, BlockPos pos) {
		BlockState s = level.getBlockState(pos);
		return FluidUtils.isFluidStateOverrided(s) && FluidLayer.getFluidState(pos, level.getChunkAt(pos)) != null;
	}

	/** " (falling)", " (fluid layer)" as translated arguments, or "". */
	private static String flags(ServerLevel level, BlockPos pos, FluidState fs) {
		String f = "";
		if (PressureSearch.isFalling(fs)) f += key("ocwo.debug.flag.falling");
		if (inFluidLayer(level, pos)) f += (f.isEmpty() ? "" : "+") + key("ocwo.debug.flag.layer");
		if (io.github.nagsfov.ocwo.fluids.WaterRest.isResting(level, pos)) f += (f.isEmpty() ? "" : "+") + key("ocwo.debug.flag.resting");
		return f;
	}

	private static String num(double v) {
		return String.format(Locale.ROOT, "%.2f", v);
	}

	private static void headWater(ServerLevel level, ServerPlayer player, List<String> out) {
		Vec3 eye = player.getEyePosition();
		BlockPos pos = BlockPos.containing(eye);
		if (io.github.nagsfov.ocwo.fluids.WaterRest.enabled(level)) {
			var ledger = io.github.nagsfov.ocwo.fluids.WaterReserves.get(level);
			out.add(line("ocwo.debug.head.reserve", ledger.reserve(level, pos), levels(ledger.clouds(pos))));
		}
		FluidState fs = level.getFluidState(pos);
		if (fs.isEmpty() || !(fs.getType() instanceof FlowingFluid)) {
			out.add(line("ocwo.debug.head.none"));
			return;
		}
		out.add(line("ocwo.debug.head.fluid", fluidName(fs), fs.getAmount(), flags(level, pos, fs)));
		PressureSearch.Inspection in = CustomFluidTicks.get(level).bodies().inspect(pos);
		double depth = Math.max(0, in.highHeight() - eye.y * FluidUtils.MAX_LEVEL);
		out.add(line("ocwo.debug.head.pressure", num(depth / FluidUtils.MAX_LEVEL), String.format(Locale.ROOT, "%.0f", depth),
				num(in.highHeight() / (double) FluidUtils.MAX_LEVEL)));
		if (!in.complete()) out.add(line("ocwo.debug.body.incomplete", in.columns()));
		else if (in.balanced()) out.add(line("ocwo.debug.body.balanced", in.columns()));
		else out.add(line("ocwo.debug.body.pending", num(in.lowHeight() / (double) FluidUtils.MAX_LEVEL), in.columns()));
	}

	private static void lookedAt(ServerLevel level, ServerPlayer player, List<String> out) {
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getViewVector(1.0F).scale(REACH));
		BlockHitResult water = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));
		BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		if (water.getType() == HitResult.Type.BLOCK && !water.getBlockPos().equals(hit.getBlockPos())) {
			BlockPos wp = water.getBlockPos();
			FluidState wfs = level.getFluidState(wp);
			if (!wfs.isEmpty()) out.add(line("ocwo.debug.block.water", wp.toShortString(), fluidName(wfs), wfs.getAmount(), flags(level, wp, wfs)));
		}
		if (hit.getType() != HitResult.Type.BLOCK) {
			out.add(line("ocwo.debug.block.none"));
			return;
		}
		BlockPos pos = hit.getBlockPos();
		BlockState state = level.getBlockState(pos);
		MoistureSimulation sim = CustomFluidTicks.get(level).moisture();
		String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		String orient = "";
		if (state.hasProperty(BlockStateProperties.AXIS)) orient = state.getValue(BlockStateProperties.AXIS).getName();
		else if (state.hasProperty(BlockStateProperties.FACING)) orient = state.getValue(BlockStateProperties.FACING).getName();
		out.add(line(orient.isEmpty() ? "ocwo.debug.block.id" : "ocwo.debug.block.id_axis", id, pos.toShortString(), orient));
		FluidState inner = level.getFluidState(pos);
		if (!inner.isEmpty()) out.add(line("ocwo.debug.block.inner", inner.getAmount(), flags(level, pos, inner)));
		MoistureProps props = MoistureData.get(state);
		int units = sim.units(pos);
		if (props == null || !props.holdsMoisture()) {
			out.add(props == null ? line("ocwo.debug.block.no_moisture") : line("ocwo.debug.block.no_moisture_material", props.material()));
			if (units > 0) out.add(line("ocwo.debug.block.pending_release", units));
			return;
		}
		int cap = props.capacity();
		Moisture.Stage stage = Moisture.Stage.of(units, cap);
		out.add(line(props.retains() ? "ocwo.debug.block.material_retains" : "ocwo.debug.block.material", props.material()));
		out.add(line("ocwo.debug.block.moisture", units, cap, num(units / (double) Moisture.UNITS_PER_LEVEL),
				cap / Moisture.UNITS_PER_LEVEL, 100 * units / cap, key("ocwo.debug.stage." + stage.id)));
		out.add(line("ocwo.debug.block.permeability",
				props.permeability(state, Direction.DOWN), props.permeability(state, Direction.UP),
				props.permeability(state, Direction.NORTH), props.permeability(state, Direction.SOUTH),
				props.permeability(state, Direction.EAST), props.permeability(state, Direction.WEST)));
		if (props.fixedWater() > 0) out.add(line("ocwo.debug.block.fixed_water", props.fixedWater(),
				String.format(Locale.ROOT, "%.1f", props.fixedWater() / (double) Moisture.UNITS_PER_LEVEL)));
		if (props.transformTo() != null) out.add(line("ocwo.debug.block.transform",
				BuiltInRegistries.BLOCK.getKey(props.transformTo()).toString(), key("ocwo.debug.stage." + props.transformStage().id)));
		if (props.revertTo() != null) out.add(line("ocwo.debug.block.revert",
				BuiltInRegistries.BLOCK.getKey(props.revertTo()).toString(), key("ocwo.debug.stage." + props.revertStage().id)));
		out.add(line(sim.isActive(pos) ? "ocwo.debug.block.active" : "ocwo.debug.block.idle"));
	}

	private static void simulation(ServerLevel level, List<String> out) {
		CustomFluidTicks ticks = CustomFluidTicks.get(level);
		var rules = level.getGameRules();
		out.add(line("ocwo.debug.sim.cost", num(ticks.debugTotalMillis()), num(ticks.debugFlowMillis()),
				num(ticks.debugPressureMillis()), num(ticks.debugMoistureMillis())));
		out.add(line("ocwo.debug.sim.queues", ticks.moisture().activeCount(), ticks.pressureQueueSize()));
		out.add(line("ocwo.debug.sim.mspt", String.format(Locale.ROOT, "%.1f", level.getServer().getAverageTickTimeNanos() / 1e6)));
		WaterBodies b = ticks.bodies();
		out.add(line("ocwo.debug.sim.quotas", b.distance(), b.bodyCount(), b.columnCount()));
		out.add(line("ocwo.debug.sim.limits", b.lastBodiesWorked, b.lastTransfers, ticks.lastDeferred(),
				rules.get(OcwoFluidGameRules.PRESSURE_FLOW_MAX)));
	}
}
