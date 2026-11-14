package io.github.nagsfov.ocwo.debug;

import com.mojang.brigadier.CommandDispatcher;
import io.github.nagsfov.ocwo.fluids.CustomFluidTicks;
import io.github.nagsfov.ocwo.fluids.FluidUtils;
import io.github.nagsfov.ocwo.fluids.OcwoFluidGameRules;
import io.github.nagsfov.ocwo.fluids.PressureSearch;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OCWO debug panel: {@code /ocwo debug} toggles, for the player who runs it, a panel at the top right of the
 * screen, refreshed every {@link #PERIOD} ticks, with the water at head height (level, falling, fluid layer,
 * pressure), the block looked at (moisture, permeability, state change…) and the cost of our water code.
 * <p>
 * Computed on the server (pressure and moisture only exist there) and sent only to the players who turned it on;
 * nothing runs, and no time is measured, when nobody uses it.
 */
public final class OcwoDebug {

	private static final Set<UUID> ENABLED = ConcurrentHashMap.newKeySet();
	private static final int PERIOD = 10;
	private static final double REACH = 6.0;
	private static volatile boolean active;

	private OcwoDebug() {
	}

	/** True while at least one player has the panel: the water code then measures its own time. */
	public static boolean isActive() {
		return active;
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("ocwo")
				.then(Commands.literal("debug")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(ctx -> {
							ServerPlayer player = ctx.getSource().getPlayerOrException();
							boolean on = !ENABLED.remove(player.getUUID());
							if (on) ENABLED.add(player.getUUID());
							active = !ENABLED.isEmpty();
							if (!on) Services.PLATFORM.sendDebug(player, new DebugPayload(List.of()));
							ctx.getSource().sendSuccess(() -> Component.literal(on
									? "Panneau de débogage OCWO affiché (en haut à droite). /ocwo debug pour le masquer."
									: "Panneau de débogage OCWO masqué."), false);
							return on ? 1 : 0;
						})));
	}

	/** Called every tick for each level, at the end of its fluid tick. */
	public static void tick(ServerLevel level) {
		if (!active || level.getGameTime() % PERIOD != 0) return;
		for (ServerPlayer player : level.players()) {
			if (ENABLED.contains(player.getUUID())) Services.PLATFORM.sendDebug(player, new DebugPayload(lines(level, player)));
		}
	}

	public static void onPlayerLeft(UUID id) {
		ENABLED.remove(id);
		active = !ENABLED.isEmpty();
	}

	// ------------------------------------------------------------------ content

	private static List<String> lines(ServerLevel level, ServerPlayer player) {
		List<String> out = new ArrayList<>();
		out.add("§b§lOCWO");
		headWater(level, player, out);
		lookedAt(level, player, out);
		global(level, out);
		return out;
	}

	private static int searchLimit(ServerLevel level) {
		return OcwoFluidGameRules.PRESSURE_SEARCH_LIMIT == null ? 256 : level.getGameRules().get(OcwoFluidGameRules.PRESSURE_SEARCH_LIMIT);
	}

	private static String fluidName(FluidState fs) {
		return BuiltInRegistries.FLUID.getKey(fs.getType()).getPath().replace("flowing_", "");
	}

	private static boolean inFluidLayer(ServerLevel level, BlockPos pos) {
		BlockState s = level.getBlockState(pos);
		return FluidUtils.isFluidStateOverrided(s) && FluidLayer.getFluidState(pos, level.getChunkAt(pos)) != null;
	}

	private static void headWater(ServerLevel level, ServerPlayer player, List<String> out) {
		Vec3 eye = player.getEyePosition();
		BlockPos pos = BlockPos.containing(eye);
		FluidState fs = level.getFluidState(pos);
		out.add("§7— Tête —");
		if (fs.isEmpty() || !(fs.getType() instanceof FlowingFluid)) {
			out.add("§7hors de l'eau");
			return;
		}
		out.add(String.format(Locale.ROOT, "§f%s %d/8%s%s", fluidName(fs), fs.getAmount(),
				PressureSearch.isFalling(fs) ? " §e(chute)" : "", inFluidLayer(level, pos) ? " §d(couche fluide)" : ""));
		PressureSearch.Inspection in = PressureSearch.inspect(level, pos, Math.max(1, searchLimit(level)));
		double eyeLevels = eye.y * FluidUtils.MAX_LEVEL;
		double depth = Math.max(0, in.highHeight() - eyeLevels);
		out.add(String.format(Locale.ROOT, "§bpression : %.2f blocs (%.0f niveaux) sous la surface y=%.2f",
				depth / FluidUtils.MAX_LEVEL, depth, in.highHeight() / (double) FluidUtils.MAX_LEVEL));
		if (!in.complete()) out.add(String.format(Locale.ROOT, "§7masse : plus de %d colonnes, analyse incomplète (reprise plus tard)", in.columns()));
		else if (in.balanced()) out.add(String.format(Locale.ROOT, "§amasse : équilibrée (%d colonnes)", in.columns()));
		else out.add(String.format(Locale.ROOT, "§6masse : transferts en attente, point bas y=%.2f (%d colonnes)",
				in.lowHeight() / (double) FluidUtils.MAX_LEVEL, in.columns()));
	}

	private static void lookedAt(ServerLevel level, ServerPlayer player, List<String> out) {
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getViewVector(1.0F).scale(REACH));
		out.add("§7— Bloc visé —");
		BlockHitResult water = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));
		BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		if (water.getType() == HitResult.Type.BLOCK && !water.getBlockPos().equals(hit.getBlockPos())) {
			BlockPos wp = water.getBlockPos();
			FluidState wfs = level.getFluidState(wp);
			if (!wfs.isEmpty()) out.add(String.format(Locale.ROOT, "§feau visée %s : %s %d/8%s%s", wp.toShortString(), fluidName(wfs),
					wfs.getAmount(), PressureSearch.isFalling(wfs) ? " §e(chute)" : "", inFluidLayer(level, wp) ? " §d(couche fluide)" : ""));
		}
		if (hit.getType() != HitResult.Type.BLOCK) {
			out.add("§7aucun bloc");
			return;
		}
		BlockPos pos = hit.getBlockPos();
		BlockState state = level.getBlockState(pos);
		MoistureSimulation sim = CustomFluidTicks.get(level).moisture();
		String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		String orient = "";
		if (state.hasProperty(BlockStateProperties.AXIS)) orient = " axe " + state.getValue(BlockStateProperties.AXIS).getName();
		else if (state.hasProperty(BlockStateProperties.FACING)) orient = " vers " + state.getValue(BlockStateProperties.FACING).getName();
		out.add("§f" + id + " §7" + pos.toShortString() + orient);
		FluidState inner = level.getFluidState(pos);
		if (!inner.isEmpty()) out.add(String.format(Locale.ROOT, "§feau dans la case : %d/8%s", inner.getAmount(),
				inFluidLayer(level, pos) ? " §d(couche fluide)" : ""));
		MoistureProps props = MoistureData.get(state);
		int units = sim.units(pos);
		if (props == null || !props.holdsMoisture()) {
			out.add("§7pas d'humidité" + (props != null ? " (matériau " + props.material() + ")" : "")
					+ (units > 0 ? ", §e" + units + " u en attente de libération" : ""));
			return;
		}
		int cap = props.capacity();
		Moisture.Stage stage = Moisture.Stage.of(units, cap);
		String color = switch (stage) {
			case SEC -> "§e";
			case HUMIDE -> "§a";
			case MOUILLE -> "§3";
			case SATURE -> "§9";
		};
		out.add(String.format(Locale.ROOT, "§fmatériau %s%s", props.material(), props.retains() ? " §7(retient l'eau)" : ""));
		out.add(String.format(Locale.ROOT, "%shumidité %d/%d u = %.2f/%d niveaux (%d %%), stade %s", color, units, cap,
				units / (double) Moisture.UNITS_PER_LEVEL, cap / Moisture.UNITS_PER_LEVEL, 100 * units / cap, stage.id));
		out.add(String.format(Locale.ROOT, "§fperméabilité ↓%d ↑%d N%d S%d E%d O%d",
				props.permeability(state, Direction.DOWN), props.permeability(state, Direction.UP),
				props.permeability(state, Direction.NORTH), props.permeability(state, Direction.SOUTH),
				props.permeability(state, Direction.EAST), props.permeability(state, Direction.WEST)));
		if (props.fixedWater() > 0) out.add(String.format(Locale.ROOT, "§feau fixe %d u (%.1f niveaux)",
				props.fixedWater(), props.fixedWater() / (double) Moisture.UNITS_PER_LEVEL));
		if (props.transformTo() != null) out.add("§fdevient " + BuiltInRegistries.BLOCK.getKey(props.transformTo())
				+ " au stade " + props.transformStage().id);
		if (props.revertTo() != null) out.add("§fredevient " + BuiltInRegistries.BLOCK.getKey(props.revertTo())
				+ " sous le stade " + props.revertStage().id);
		out.add(sim.isActive(pos) ? "§asimulé en ce moment" : "§7au repos (pas dans la simulation)");
	}

	private static void global(ServerLevel level, List<String> out) {
		CustomFluidTicks ticks = CustomFluidTicks.get(level);
		out.add("§7— Monde —");
		out.add(String.format(Locale.ROOT, "§feau : %.2f ms/tick (écoulement %.2f, pression %.2f, humidité %.2f)",
				ticks.debugTotalMillis(), ticks.debugFlowMillis(), ticks.debugPressureMillis(), ticks.debugMoistureMillis()));
		out.add(String.format(Locale.ROOT, "§fblocs humides actifs %d, file de pression %d", ticks.moisture().activeCount(),
				ticks.pressureQueueSize()));
		out.add(String.format(Locale.ROOT, "§fMSPT serveur %.1f ms", level.getServer().getAverageTickTimeNanos() / 1e6));
	}
}
