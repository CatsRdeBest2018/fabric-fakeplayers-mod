package com.example;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import static net.minecraft.commands.Commands.literal;

/**
 * Commands to control fake player presence.
 * /fakeplayers force    -> keep all fake players online
 * /fakeplayers schedule -> resume time-based schedule
 */
public final class FakePlayerCommands {
	private FakePlayerCommands() {}

	public static void register(FakePlayerScheduler scheduler) {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher, scheduler));
	}

	private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, FakePlayerScheduler scheduler) {
		dispatcher.register(
			literal("fakeplayers")
				.requires(src -> src.hasPermission(3))
				.then(literal("force").executes(ctx -> force(ctx, scheduler)))
				.then(literal("schedule").executes(ctx -> schedule(ctx, scheduler)))
				.then(literal("reload").executes(ctx -> reload(ctx)))
				.then(literal("list").executes(ctx -> list(ctx, scheduler)))
		);
	}

	private static int force(CommandContext<CommandSourceStack> ctx, FakePlayerScheduler scheduler) {
		scheduler.forceOnlineAll();
		ctx.getSource().sendSuccess(() -> Component.literal("Fake players forced online."), false);
		return 1;
	}

	private static int schedule(CommandContext<CommandSourceStack> ctx, FakePlayerScheduler scheduler) {
		scheduler.resumeSchedule();
		ctx.getSource().sendSuccess(() -> Component.literal("Fake players back to scheduled behavior."), false);
		return 1;
	}

	private static int reload(CommandContext<CommandSourceStack> ctx) {
		boolean ok = com.example.config.FakePlayerConfigManager.reload(ctx.getSource().getServer());
		if (ok) {
			ctx.getSource().sendSuccess(() -> Component.literal("Reloaded fake player config."), true);
		} else {
			ctx.getSource().sendFailure(Component.literal("Failed to reload fake player config. See log for details."));
		}
		return ok ? 1 : 0;
	}

	private static int list(CommandContext<CommandSourceStack> ctx, FakePlayerScheduler scheduler) {
		var statuses = scheduler.getStatuses();
		if (statuses.isEmpty()) {
			ctx.getSource().sendSuccess(() -> Component.literal("No fake players configured."), false);
			return 1;
		}
		ctx.getSource().sendSuccess(() -> Component.literal("Fake players: " + statuses.size()), false);
		for (var status : statuses) {
			StringBuilder sb = new StringBuilder();
			sb.append(status.name()).append(" (").append(status.id()).append(") - ");
			if (status.online()) {
				sb.append("online");
				if (status.inBreak()) {
					sb.append(" (on break");
					if (status.breakUntil() != null) {
						sb.append(" until ").append(status.breakUntil().toLocalTime());
					}
					sb.append(")");
				} else {
					if (status.nextBreakStart() != null) {
						sb.append(", next break at ").append(status.nextBreakStart().toLocalTime());
					}
				}
			} else {
				if (status.inActiveWindow()) {
					sb.append("offline (window active)");
					if (status.nextLogin() != null) {
						sb.append(", next login at ").append(status.nextLogin().toLocalTime());
					}
				} else {
					sb.append("offline (outside window)");
				}
			}
			ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
		}
		return 1;
	}
}
