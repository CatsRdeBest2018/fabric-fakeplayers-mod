package com.example;

import com.example.config.FakePlayerConfig;
import com.example.config.FakePlayerConfigManager;
import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundTabListPacket;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.RemoteChatSession;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public class ExampleMod implements ModInitializer {
	public static final String MOD_ID = "modid";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static FakePlayerConfig CONFIG;
	public static final FakePlayerScheduler SCHEDULER = new FakePlayerScheduler();

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			SCHEDULER.bind(server);
			FakePlayerConfigManager.loadOrCreate(server);
			CONFIG = FakePlayerConfigManager.getConfig();
			LOGGER.info("Loaded fake player scheduler with {} configured bots.", CONFIG.bots.size());
			refreshTabListForAllRealPlayers(server);
		});

		ServerLifecycleEvents.SERVER_STOPPED.register(server -> SCHEDULER.shutdown());
		ServerTickEvents.END_SERVER_TICK.register(SCHEDULER::tick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			SCHEDULER.handleRealPlayerJoin(handler.player);
			refreshTabListForAllRealPlayers(server);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> refreshTabListForAllRealPlayers(server));
		SilentBlocker.register();
		FakePlayerCommands.register(SCHEDULER);
	}

	public static FakePlayerConfig getFakePlayerConfig() {
		return CONFIG;
	}

	public static FakePlayerScheduler getScheduler() {
		return SCHEDULER;
	}

	public static List<NameAndId> getFakePlayers() {
		return SCHEDULER.getActivePlayers();
	}

	public static List<ClientboundPlayerInfoUpdatePacket.Entry> getFakeTabEntries() {
		return SCHEDULER.getActiveTabEntries();
	}

	public static ServerStatus withFakePlayers(ServerStatus status, PlayerList playerList) {
		int realCount = playerList.getPlayerCount();
		int fakeCount = SCHEDULER.getActivePlayers().size();
		int maxPlayers = Math.max(playerList.getMaxPlayers(), realCount + fakeCount);

		List<NameAndId> sample = new ArrayList<>(status.players()
			.map(ServerStatus.Players::sample)
			.orElseGet(ArrayList::new));

		Set<String> seenNames = new HashSet<>();
		for (NameAndId existing : sample) {
			seenNames.add(existing.name().toLowerCase(Locale.ROOT));
		}

		for (NameAndId fake : SCHEDULER.getActivePlayers()) {
			if (seenNames.add(fake.name().toLowerCase(Locale.ROOT))) {
				sample.add(fake);
			}
		}

		ServerStatus.Players players = new ServerStatus.Players(maxPlayers, realCount + fakeCount, sample);
		return new ServerStatus(status.description(), Optional.of(players), status.version(), status.favicon(), status.enforcesSecureChat());
	}

	public static ClientboundPlayerInfoUpdatePacket.Entry toEntry(GameProfile profile) {
		Component displayName = Component.literal(profile.name());
		// Listed=true, latency=0, gamemode=survival, display name for tab, no hat, list order=0, no chat session
		return new ClientboundPlayerInfoUpdatePacket.Entry(
			profile.id(),
			profile,
			true,
			0,
			GameType.SURVIVAL,
			displayName,
			false,
			0,
			(RemoteChatSession.Data) null
		);
	}

	public static ClientboundPlayerInfoUpdatePacket.Entry toEntry(NameAndId fake) {
		return toEntry(new GameProfile(fake.id(), fake.name()));
	}

	public static void refreshTabListForAllRealPlayers(net.minecraft.server.MinecraftServer server) {
		int realCount = server.getPlayerList().getPlayerCount();
		var fakeEntries = getFakeTabEntries();
		int fakeCount = fakeEntries.size();
		int total = realCount + fakeCount;

		// Send header/footer with total count
		Component header = Component.literal("Welcome");
		Component footer = Component.literal("Players online: " + total);
		ClientboundTabListPacket tabPacket = new ClientboundTabListPacket(header, footer);

		// Remove current fake entries
		if (fakeCount > 0) {
			ClientboundPlayerInfoRemovePacket remove = new ClientboundPlayerInfoRemovePacket(
				SCHEDULER.getActivePlayers().stream().map(NameAndId::id).toList()
			);
			server.getPlayerList().broadcastAll(remove);
		}

		EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions = EnumSet.of(
			ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
			ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
			ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
			ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
			ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME
		);

		for (ServerPlayer viewer : server.getPlayerList().getPlayers()) {
			ClientboundPlayerInfoUpdatePacket addPacket = new ClientboundPlayerInfoUpdatePacket(
				actions,
				java.util.Collections.emptyList()
			);
			var acc = (com.example.mixin.ClientboundPlayerInfoUpdatePacketAccessor) addPacket;
			acc.setEntries(fakeEntries);
			viewer.connection.send(addPacket);
			viewer.connection.send(tabPacket);
		}
	}
}
