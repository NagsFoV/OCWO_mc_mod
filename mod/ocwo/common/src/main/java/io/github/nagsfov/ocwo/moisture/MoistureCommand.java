package io.github.nagsfov.ocwo.moisture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import io.github.nagsfov.ocwo.fluids.CustomFluidTicks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Debug command: {@code /ocwo moisture [pos]} shows the moisture of a block (material, units, levels, stage,
 * permeability per face); {@code /ocwo moisture set <pos> <units>} (operators) sets it for tests.
 */
public final class MoistureCommand {

	private MoistureCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("ocwo")
				.then(Commands.literal("moisture")
						.executes(ctx -> show(ctx, BlockPos.containing(ctx.getSource().getPosition()).below()))
						.then(Commands.argument("pos", BlockPosArgument.blockPos())
								.executes(ctx -> show(ctx, BlockPosArgument.getLoadedBlockPos(ctx, "pos"))))
						.then(Commands.literal("set")
								.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.then(Commands.argument("pos", BlockPosArgument.blockPos())
										.then(Commands.argument("units", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 32767))
												.executes(ctx -> set(ctx, BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
														com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "units"))))))));
	}

	private static int show(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
		ServerLevel level = ctx.getSource().getLevel();
		BlockState state = level.getBlockState(pos);
		MoistureProps props = MoistureData.get(state);
		MoistureSimulation sim = CustomFluidTicks.get(level).moisture();
		int units = sim.units(pos);
		String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
		StringBuilder sb = new StringBuilder();
		sb.append(id).append(" @ ").append(pos.toShortString()).append('\n');
		if (io.github.nagsfov.ocwo.sponge.OcwoSponges.isAnySponge(state)) {
			// a sponge is a bucket: its fill level, in whole levels (stored where moisture is, but it is not moisture)
			int fill = io.github.nagsfov.ocwo.sponge.SpongeLogic.poolUnits(level, pos) / Moisture.UNITS_PER_LEVEL;
			int cap = io.github.nagsfov.ocwo.sponge.SpongeLogic.poolCapacity(level, pos) / Moisture.UNITS_PER_LEVEL;
			sb.append("Éponge : remplissage ").append(fill).append(" / ").append(cap).append(" niveaux");
		} else if (props == null || !props.holdsMoisture()) {
			sb.append("Pas d'humidité (matériau ").append(props == null ? "aucun" : props.material()).append(")");
			if (units > 0) sb.append(" - en attente de libération : ").append(units).append(" unités");
		} else {
			Moisture.Stage stage = Moisture.Stage.of(units, props.capacity());
			sb.append("Matériau ").append(props.material())
					.append(" | humidité ").append(units).append('/').append(props.capacity()).append(" unités (")
					.append(String.format("%.2f", units / (double) Moisture.UNITS_PER_LEVEL)).append(" niveaux)")
					.append(" | stade ").append(stage.id);
			if (props.retains()) sb.append(" | retient l'eau");
			sb.append("\nPerméabilité : ");
			for (Direction d : Direction.values()) sb.append(d.getName()).append('=').append(props.permeability(state, d)).append(' ');
			if (props.transformTo() != null) sb.append("\nDevient ")
					.append(BuiltInRegistries.BLOCK.getKey(props.transformTo())).append(" au stade ").append(props.transformStage().id);
			if (props.revertTo() != null) sb.append("\nRedevient ")
					.append(BuiltInRegistries.BLOCK.getKey(props.revertTo())).append(" sous le stade ").append(props.revertStage().id);
		}
		sb.append("\nBlocs actifs dans la simulation : ").append(sim.activeCount());
		String text = sb.toString();
		ctx.getSource().sendSuccess(() -> Component.literal(text), false);
		return units;
	}

	private static int set(CommandContext<CommandSourceStack> ctx, BlockPos pos, int units) {
		MoistureSimulation sim = CustomFluidTicks.get(ctx.getSource().getLevel()).moisture();
		sim.setUnits(pos, units);
		sim.activate(pos);
		ctx.getSource().sendSuccess(() -> Component.literal("Humidité fixée à " + units + " unités en " + pos.toShortString()), true);
		return units;
	}
}
