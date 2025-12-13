package com.example;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Simple silent-ban manager.
 * - Keeps an in-memory, lowercase set of usernames.
 * - Blocks login in the ALLOW_LOGIN phase with a believable error string (no ban wording).
 * - Exposes /silentban and /silentunban commands for ops (permission level >= 3).
 */
public final class SilentBlocker {
	private static final Logger LOGGER = LoggerFactory.getLogger("modid-silentban");
	private static final Path BAN_FILE = FabricLoader.getInstance().getConfigDir().resolve("modid-silentbans.txt");
	private static final Set<String> SILENT_BANNED = new HashSet<>();
	private static final List<Component> ERROR_MESSAGES = List.of(
		Component.literal("Internal exception: java.io.IOException: Connection reset"),
		Component.literal("Failed to verify username."),
		Component.literal("Timed out."),
		Component.literal("Disconnected"),
		Component.literal("Internal server error")
	);
	private static final Random RANDOM = new Random();

	private SilentBlocker() {}

	public static void register() {
		// Persist bans between restarts.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> loadFromDisk());
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> saveToDisk());

		// Login hook: deny login with a fake error if the name is silently banned.
		ServerLoginConnectionEvents.INIT.register((handler, server) -> onLoginCheck(handler));
		ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, synchronizer) -> onLoginCheck(handler));
		// Fallback at play join in case login hook is bypassed by some proxy.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> onJoinCheck(handler.player));

		// Command registration for /silentban and /silentunban (server only).
		CommandRegistrationCallback.EVENT.register(SilentBlocker::registerCommands);
	}

	private static void onLoginCheck(ServerLoginPacketListenerImpl handler) {
		// Use the requested username (set after the client hello) and block immediately if banned.
		String username = handler.getUserName();
		if (username == null) {
			return;
		}

		String nameKey = normalize(username);
		if (SILENT_BANNED.contains(nameKey)) {
			handler.disconnect(randomError());
		}
	}

	private static void onJoinCheck(net.minecraft.server.level.ServerPlayer player) {
		String username = player.getGameProfile().name();
		String nameKey = normalize(username);
		if (SILENT_BANNED.contains(nameKey)) {
			player.connection.disconnect(randomError());
		}
	}

	private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext registryAccess, Commands.CommandSelection environment) {
		dispatcher.register(buildBanCommand());
		dispatcher.register(buildUnbanCommand());
	}

	private static LiteralArgumentBuilder<CommandSourceStack> buildBanCommand() {
		return literal("silentban")
			.requires(src -> src.hasPermission(3))
			.then(argument("targets", net.minecraft.commands.arguments.EntityArgument.players())
				.executes(SilentBlocker::executeBanTargets))
			.then(argument("username", StringArgumentType.word())
				.executes(SilentBlocker::executeBan));
	}

	private static LiteralArgumentBuilder<CommandSourceStack> buildUnbanCommand() {
		return literal("silentunban")
			.requires(src -> src.hasPermission(3))
			.then(argument("username", StringArgumentType.word())
				.suggests(SilentBlocker::suggestBannedUsernames)
				.executes(SilentBlocker::executeUnban));
	}

	private static int executeBan(CommandContext<CommandSourceStack> ctx) {
		String raw = StringArgumentType.getString(ctx, "username");
		String key = normalize(raw);

		if (!SILENT_BANNED.add(key)) {
			ctx.getSource().sendFailure(Component.literal(raw + " is already silently banned."));
			return 0;
		}

		saveToDisk();

		// If they're online right now, drop them immediately with a fake error.
		var online = ctx.getSource().getServer().getPlayerList().getPlayerByName(raw);
		if (online != null) {
			online.connection.disconnect(randomError());
		}

		ctx.getSource().sendSuccess(() -> Component.literal("Silently banned " + raw + "."), false);
		return 1;
	}

	private static int executeBanTargets(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		var targets = net.minecraft.commands.arguments.EntityArgument.getPlayers(ctx, "targets");
		int added = 0;
		for (var player : targets) {
			String raw = player.getGameProfile().name();
			String key = normalize(raw);
			if (SILENT_BANNED.add(key)) {
				added++;
				// Optionally kick immediately with a believable error.
				player.connection.disconnect(randomError());
			}
		}
		if (added > 0) {
			saveToDisk();
			final int total = added;
			ctx.getSource().sendSuccess(() -> Component.literal("Silently banned " + total + " player(s)."), false);
			return added;
		}
		ctx.getSource().sendFailure(Component.literal("All selected players were already silently banned."));
		return 0;
	}

	private static int executeUnban(CommandContext<CommandSourceStack> ctx) {
		String raw = StringArgumentType.getString(ctx, "username");
		String key = normalize(raw);

		if (SILENT_BANNED.remove(key)) {
			saveToDisk();
			ctx.getSource().sendSuccess(() -> Component.literal("Silently unbanned " + raw + "."), false);
			return 1;
		}

		ctx.getSource().sendFailure(Component.literal(raw + " was not silently banned."));
		return 0;
	}

	private static java.util.concurrent.CompletableFuture<Suggestions> suggestBannedUsernames(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
		return SharedSuggestionProvider.suggest(SILENT_BANNED.stream().toList(), builder);
	}

	private static String normalize(String name) {
		return name.toLowerCase(Locale.ROOT);
	}

	private static Component randomError() {
		return ERROR_MESSAGES.get(RANDOM.nextInt(ERROR_MESSAGES.size()));
	}

	private static void loadFromDisk() {
		if (!Files.exists(BAN_FILE)) {
			return;
		}
		try {
			List<String> lines = Files.readAllLines(BAN_FILE, StandardCharsets.UTF_8);
			SILENT_BANNED.clear();
			for (String line : lines) {
				String trimmed = line.trim();
				if (!trimmed.isEmpty()) {
					SILENT_BANNED.add(normalize(trimmed));
				}
			}
			LOGGER.info("Loaded {} silent bans.", SILENT_BANNED.size());
		} catch (IOException e) {
			LOGGER.warn("Failed to load silent bans from {}", BAN_FILE, e);
		}
	}

	private static void saveToDisk() {
		try {
			Files.createDirectories(BAN_FILE.getParent());
			Files.write(BAN_FILE, SILENT_BANNED, StandardCharsets.UTF_8);
		} catch (IOException e) {
			LOGGER.warn("Failed to save silent bans to {}", BAN_FILE, e);
		}
	}
}
