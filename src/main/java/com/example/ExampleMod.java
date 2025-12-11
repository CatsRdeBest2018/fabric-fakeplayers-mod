package com.example;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
import net.minecraft.network.chat.RemoteChatSession;

public class ExampleMod implements ModInitializer {
	public static final String MOD_ID = "modid";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final int FAKE_PLAYER_COUNT = 5;
	private static final List<NameAndId> FAKE_PLAYERS = Collections.unmodifiableList(generateFakePlayers());
	private static final List<ClientboundPlayerInfoUpdatePacket.Entry> FAKE_TAB_ENTRIES = Collections.unmodifiableList(buildFakeEntries());

	@Override
	public void onInitialize() {
		LOGGER.info("Loaded fake player list with {} always-online players.", FAKE_PLAYERS.size());
	}

	public static List<NameAndId> getFakePlayers() {
		return FAKE_PLAYERS;
	}

	public static List<ClientboundPlayerInfoUpdatePacket.Entry> getFakeTabEntries() {
		return FAKE_TAB_ENTRIES;
	}

	public static ServerStatus withFakePlayers(ServerStatus status, PlayerList playerList) {
		int realCount = playerList.getPlayerCount();
		int maxPlayers = Math.max(playerList.getMaxPlayers(), realCount + FAKE_PLAYERS.size());

		List<NameAndId> sample = new ArrayList<>(status.players()
			.map(ServerStatus.Players::sample)
			.orElseGet(ArrayList::new));

		Set<String> seenNames = new HashSet<>();
		for (NameAndId existing : sample) {
			seenNames.add(existing.name().toLowerCase(Locale.ROOT));
		}

		for (NameAndId fake : FAKE_PLAYERS) {
			if (seenNames.add(fake.name().toLowerCase(Locale.ROOT))) {
				sample.add(fake);
			}
		}

		ServerStatus.Players players = new ServerStatus.Players(maxPlayers, realCount + FAKE_PLAYERS.size(), sample);
		return new ServerStatus(status.description(), Optional.of(players), status.version(), status.favicon(), status.enforcesSecureChat());
	}

	private static List<NameAndId> generateFakePlayers() {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		List<NameAndId> players = new ArrayList<>(FAKE_PLAYER_COUNT);

		for (int i = 0; i < FAKE_PLAYER_COUNT; i++) {
			String name = randomName(random);
			players.add(new NameAndId(UUID.randomUUID(), name));
		}

		return players;
	}

	private static String randomName(ThreadLocalRandom random) {
		String[] first = {"Nova", "Echo", "Quartz", "Vapor", "Fable", "Orbit", "Pixel", "Jade", "Onyx", "Rune"};
		String[] second = {"Drifter", "Scribe", "Wanderer", "Whisper", "Runner", "Warden", "Diver", "Glider", "Smith", "Caster"};

		String primary = first[random.nextInt(first.length)];
		String suffix = second[random.nextInt(second.length)];
		int digits = random.nextInt(10, 99);
		return primary + suffix + digits;
	}

	private static List<ClientboundPlayerInfoUpdatePacket.Entry> buildFakeEntries() {
		List<ClientboundPlayerInfoUpdatePacket.Entry> entries = new ArrayList<>(FAKE_PLAYER_COUNT);

		for (NameAndId fake : FAKE_PLAYERS) {
			GameProfile profile = new GameProfile(fake.id(), fake.name());
			Component displayName = Component.literal(fake.name());
			// Listed=true, latency=0, gamemode=survival, display name for tab, no hat, list order=0, no chat session
			ClientboundPlayerInfoUpdatePacket.Entry entry = new ClientboundPlayerInfoUpdatePacket.Entry(
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
			entries.add(entry);
		}

		return entries;
	}
}
