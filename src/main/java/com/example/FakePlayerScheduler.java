package com.example;

import com.example.config.ActiveWindowConfig;
import com.example.config.BotConfig;
import com.example.config.FakePlayerConfig;
import com.example.config.GlobalConfig;
import com.google.common.collect.HashMultimap;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Drives fake player presence: config-defined schedules, interval/break cycling, and TAB updates.
 */
public class FakePlayerScheduler {
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
	private static final int LOGIN_STAGGER_SECONDS = 30;
	private static final ZoneId EASTERN = ZoneId.of("America/New_York");
	private static final List<String> DEATH_MESSAGES = List.of(
		"%s fell from a high place",
		"%s tried to swim in lava",
		"%s was slain by Zombie",
		"%s drowned",
		"%s was shot by Skeleton",
		"%s suffocated in a wall",
		"%s was slain by Enderman",
		"%s hit the ground too hard",
		"%s burned to death",
		"%s was slain by Spider",
		"%s was impaled by Drowned",
		"%s was blown up by Creeper"
	);

	private final List<FakePlayerProfile> profiles = new ArrayList<>();
	private final List<PendingChat> pendingChats = new ArrayList<>();
	private final Random random = new Random();
	private final HttpClient httpClient = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(5))
		.build();
	private final java.util.Map<UUID, SkinTextures> textureCache = new ConcurrentHashMap<>();
	private MinecraftServer server;
	private boolean forcedOnline = false;
	private GlobalConfig globalConfig = new GlobalConfig();

	public void bind(MinecraftServer server) {
		this.server = server;
	}

	/**
	 * Reload configuration and rebuild internal profiles.
	 *
	 * @param server running server
	 * @param config latest fake player config
	 */
	public void applyConfig(MinecraftServer server, FakePlayerConfig config) {
		this.server = server;
		LocalDateTime now = currentEasternTime();
		for (FakePlayerProfile profile : profiles) {
			if (profile.online || profile.inBreak) {
				goOffline(profile, now);
			}
		}
		this.globalConfig = normalizeGlobalConfig(config != null ? config.global : null);
		rebuildProfiles(config != null ? config.bots : null);
		pendingChats.clear();
		forcedOnline = false;
	}

	public void shutdown() {
		pendingChats.clear();
		for (FakePlayerProfile profile : profiles) {
			if (profile.online) {
				sendRemove(profile);
			}
			profile.resetState();
		}
		this.server = null;
	}

	public void tick(MinecraftServer server) {
		if (this.server == null) {
			return;
		}

		LocalDateTime now = currentEasternTime();

		if (globalConfig == null || !globalConfig.enabled) {
			forcedOnline = false;
			for (FakePlayerProfile profile : profiles) {
				if (profile.online || profile.inBreak) {
					goOffline(profile, now);
				}
			}
			pendingChats.clear();
			return;
		}

		if (forcedOnline) {
			for (FakePlayerProfile profile : profiles) {
				if (!profile.online) {
					goOnline(profile, now, null, false);
				}
				profile.inBreak = false;
				profile.breakUntil = null;
				profile.nextBreakStart = null;
				maybeTriggerDeath(profile, now);
			}
			deliverPendingChats(now);
			return;
		}

		for (FakePlayerProfile profile : profiles) {
			WindowInstance activeWindow = resolveActiveWindow(profile, now);

			if (activeWindow == null) {
				if (profile.online || profile.inBreak) {
					goOffline(profile, now);
				}
				profile.resetScheduleState();
				continue;
			}

			profile.currentWindowEnd = activeWindow.end();

			if (profile.online) {
				// Window end reached
				if (!now.isBefore(activeWindow.end())) {
					goOffline(profile, now);
					continue;
				}

				// Break time?
				if (profile.nextBreakStart != null && !now.isBefore(profile.nextBreakStart)) {
					startBreak(profile, now, activeWindow);
				}
			} else if (profile.inBreak) {
				// End break if time reached and still inside window
				if (profile.breakUntil != null && !now.isBefore(profile.breakUntil) && now.isBefore(activeWindow.end())) {
					endBreak(profile, now, activeWindow);
				} else if (!now.isBefore(activeWindow.end())) {
					goOffline(profile, now);
				}
			}

			if (!profile.online && !profile.inBreak) {
				if (profile.nextLogin == null) {
					profile.nextLogin = now.plusSeconds(random.nextInt(LOGIN_STAGGER_SECONDS + 1));
				}
				if (!now.isBefore(profile.nextLogin)) {
					goOnline(profile, now, activeWindow, false);
				}
			}

			if (profile.online) {
				maybeTriggerDeath(profile, now);
			}
		}

		deliverPendingChats(now);
	}

	public boolean enable() {
		if (globalConfig == null) {
			globalConfig = new GlobalConfig();
		}
		if (globalConfig.enabled) {
			return false;
		}
		globalConfig.enabled = true;
		resumeSchedule();
		return true;
	}

	public boolean disable() {
		if (globalConfig == null || !globalConfig.enabled) {
			return false;
		}
		globalConfig.enabled = false;
		forcedOnline = false;
		LocalDateTime now = currentEasternTime();
		for (FakePlayerProfile profile : profiles) {
			if (profile.online || profile.inBreak) {
				goOffline(profile, now);
			}
		}
		pendingChats.clear();
		return true;
	}

	public void handleRealPlayerJoin(ServerPlayer joining) {
		if (this.server == null || profiles.isEmpty()) {
			return;
		}

		// Ensure the joining player receives current fake tab entries.
		sendAddSnapshotTo(joining);

		List<FakePlayerProfile> online = profiles.stream().filter(FakePlayerProfile::online).toList();
		if (online.isEmpty()) {
			return;
		}

		if (random.nextDouble() <= 0.25d) {
			FakePlayerProfile speaker = online.get(random.nextInt(online.size()));
			String[] pool = {"Yo", "Hi", "Hello", "yo yo", "what's up"};
			String msg = pool[random.nextInt(pool.length)];
			long delaySeconds = 1 + random.nextInt(5);
			pendingChats.add(new PendingChat(currentEasternTime().plusSeconds(delaySeconds), speaker, msg));
		}
	}

	public List<NameAndId> getActivePlayers() {
		return profiles.stream()
			.filter(FakePlayerProfile::online)
			.map(FakePlayerProfile::nameAndId)
			.collect(Collectors.toUnmodifiableList());
	}

	public List<ClientboundPlayerInfoUpdatePacket.Entry> getActiveTabEntries() {
		return profiles.stream()
			.filter(FakePlayerProfile::online)
			.map(FakePlayerProfile::entry)
			.collect(Collectors.toUnmodifiableList());
	}

	/**
	 * Snapshot of all known fake players and their current state.
	 */
	public List<FakePlayerStatus> getStatuses() {
		LocalDateTime now = currentEasternTime();
		List<FakePlayerStatus> statuses = new ArrayList<>();
		for (FakePlayerProfile profile : profiles) {
			WindowInstance window = resolveActiveWindow(profile, now);
			boolean inWindow = window != null;
			statuses.add(new FakePlayerStatus(
				profile.nameAndId().name(),
				profile.nameAndId().id(),
				profile.online,
				profile.inBreak,
				inWindow,
				profile.nextBreakStart,
				profile.breakUntil,
				profile.nextLogin,
				profile.currentWindowEnd,
				profile.nextDeath
			));
		}
		return statuses;
	}

	public boolean forceOnlineAll() {
		if (globalConfig == null || !globalConfig.enabled) {
			return false;
		}

		this.forcedOnline = true;
		for (FakePlayerProfile profile : profiles) {
			profile.inBreak = false;
			profile.breakUntil = null;
			profile.nextBreakStart = null;
			if (!profile.online) {
				goOnline(profile, currentEasternTime(), null, false);
			} else if (profile.nextDeath == null) {
				scheduleNextDeath(profile, currentEasternTime());
			}
		}
		return true;
	}

	public void resumeSchedule() {
		this.forcedOnline = false;
		LocalDateTime now = currentEasternTime();
		for (FakePlayerProfile profile : profiles) {
			if (profile.online || profile.inBreak) {
				goOffline(profile, now);
			}
			profile.resetScheduleState();
		}
	}

	private void rebuildProfiles(List<BotConfig> bots) {
		profiles.clear();
		if (bots == null || bots.isEmpty()) {
			return;
		}

		for (BotConfig bot : bots) {
			if (bot == null || !bot.enabled || bot.name == null || bot.name.isBlank()) {
				continue;
			}
			List<ActiveWindow> windows = parseWindows(bot.activeWindows);
			if (windows.isEmpty()) {
				continue;
			}
			UUID id = resolveUuid(bot);
			NameAndId nameAndId = new NameAndId(id, bot.name);
			GameProfile gameProfile = buildProfile(bot, id);
			ClientboundPlayerInfoUpdatePacket.Entry entry = ExampleMod.toEntry(gameProfile);
			FakePlayerProfile fakeProfile = new FakePlayerProfile(nameAndId, gameProfile, entry, windows);
			fakeProfile.nextDeath = null;
			profiles.add(fakeProfile);
		}
	}

	private List<ActiveWindow> parseWindows(List<ActiveWindowConfig> configs) {
		List<ActiveWindow> windows = new ArrayList<>();
		if (configs == null) {
			return windows;
		}
		for (ActiveWindowConfig window : configs) {
			if (window == null || window.startTime == null || window.endTime == null) {
				continue;
			}
			try {
				LocalTime start = LocalTime.parse(window.startTime, TIME_FORMAT);
				LocalTime end = LocalTime.parse(window.endTime, TIME_FORMAT);
				windows.add(new ActiveWindow(start, end));
			} catch (DateTimeParseException e) {
				ExampleMod.LOGGER.warn("[FakePlayers] Invalid window time: {} - {}", window.startTime, window.endTime);
			}
		}
		return windows;
	}

	private GameProfile buildProfile(BotConfig bot, UUID id) {
		var map = HashMultimap.<String, Property>create();
		SkinTextures textures = resolveSkin(bot);
		if (textures != null) {
			map.put("textures", new Property("textures", textures.value(), textures.signature()));
		}
		return new GameProfile(id, bot.name, new PropertyMap(map));
	}

	private SkinTextures resolveSkin(BotConfig bot) {
		// Explicit texture payload wins.
		if (bot.textureValue != null && bot.textureSignature != null) {
			return new SkinTextures(bot.textureValue, bot.textureSignature);
		}

		UUID skinUuid = parseUuid(bot.skinUuid);
		if (skinUuid == null) {
			return null;
		}
		SkinTextures cached = textureCache.get(skinUuid);
		if (cached != null) {
			return cached;
		}
		SkinTextures downloaded = downloadSkinTextures(skinUuid);
		if (downloaded != null) {
			textureCache.put(skinUuid, downloaded);
		}
		return downloaded;
	}

	private UUID parseUuid(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(raw);
		} catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private SkinTextures downloadSkinTextures(UUID uuid) {
		try {
			String plain = uuid.toString().replace("-", "");
			URI uri = URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + plain + "?unsigned=false");
			HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				return null;
			}
			JsonObject obj = JsonParser.parseString(response.body()).getAsJsonObject();
			JsonArray props = obj.getAsJsonArray("properties");
			if (props == null) {
				return null;
			}
			for (JsonElement el : props) {
				JsonObject prop = el.getAsJsonObject();
				if (!prop.has("name") || !prop.get("name").getAsString().equals("textures")) {
					continue;
				}
				if (!prop.has("value")) {
					continue;
				}
				String value = prop.get("value").getAsString();
				String sig = prop.has("signature") ? prop.get("signature").getAsString() : null;
				if (value != null && sig != null) {
					return new SkinTextures(value, sig);
				}
			}
		} catch (Exception ignored) {
			// Keep quiet; skin fetch failures fall back to default skin.
		}
		return null;
	}

	private UUID resolveUuid(BotConfig bot) {
		if (bot.uuid != null && !bot.uuid.isBlank()) {
			try {
				return UUID.fromString(bot.uuid);
			} catch (IllegalArgumentException ignored) {
				ExampleMod.LOGGER.warn("[FakePlayers] Invalid UUID {} for bot {}, generating deterministic fallback.", bot.uuid, bot.name);
			}
		}
		return UUID.nameUUIDFromBytes(("fakebot:" + bot.name).getBytes(StandardCharsets.UTF_8));
	}

	private GlobalConfig normalizeGlobalConfig(GlobalConfig config) {
		if (config == null) {
			return null;
		}
		if (config.minBreakMinutes < 0) {
			config.minBreakMinutes = 0;
		}
		if (config.maxBreakMinutes < 0) {
			config.maxBreakMinutes = 0;
		}
		if (config.minBreakMinutes > config.maxBreakMinutes) {
			int swap = config.minBreakMinutes;
			config.minBreakMinutes = config.maxBreakMinutes;
			config.maxBreakMinutes = swap;
		}
		if (config.minIntervalMinutes < 0) {
			config.minIntervalMinutes = 0;
		}
		if (config.maxIntervalMinutes < 0) {
			config.maxIntervalMinutes = 0;
		}
		if (config.minIntervalMinutes > config.maxIntervalMinutes) {
			int swap = config.minIntervalMinutes;
			config.minIntervalMinutes = config.maxIntervalMinutes;
			config.maxIntervalMinutes = swap;
		}
		if (config.minDeathMinutes < 0) {
			config.minDeathMinutes = 0;
		}
		if (config.maxDeathMinutes < 0) {
			config.maxDeathMinutes = 0;
		}
		if (config.minDeathMinutes > config.maxDeathMinutes) {
			int swap = config.minDeathMinutes;
			config.minDeathMinutes = config.maxDeathMinutes;
			config.maxDeathMinutes = swap;
		}
		return config;
	}

	private WindowInstance resolveActiveWindow(FakePlayerProfile profile, LocalDateTime now) {
		for (ActiveWindow window : profile.activeWindows) {
			LocalDateTime start = now.toLocalDate().atTime(window.start());
			LocalDateTime end = now.toLocalDate().atTime(window.end());
			if (!window.end().isAfter(window.start())) {
				if (now.toLocalTime().isBefore(window.start())) {
					start = start.minusDays(1);
				}
				end = end.plusDays(1);
			}
			if (!now.isBefore(start) && now.isBefore(end)) {
				return new WindowInstance(start, end);
			}
		}
		return null;
	}

	private void scheduleNextBreak(FakePlayerProfile profile, LocalDateTime now, WindowInstance window) {
		if (window == null) {
			profile.nextBreakStart = null;
			return;
		}
		int intervalMinutes = ThreadLocalRandom.current().nextInt(globalConfig.minIntervalMinutes, globalConfig.maxIntervalMinutes + 1);
		LocalDateTime candidate = now.plusMinutes(intervalMinutes);
		if (candidate.isBefore(window.end())) {
			profile.nextBreakStart = candidate;
		} else {
			profile.nextBreakStart = null;
		}
	}

	private void scheduleNextDeath(FakePlayerProfile profile, LocalDateTime now) {
		if (globalConfig == null || globalConfig.maxDeathMinutes <= 0) {
			profile.nextDeath = null;
			return;
		}
		int minutes = ThreadLocalRandom.current().nextInt(globalConfig.minDeathMinutes, globalConfig.maxDeathMinutes + 1);
		// Avoid hammering chat if min is configured to 0; push at least 1 minute out.
		if (minutes <= 0) {
			minutes = 1;
		}
		profile.nextDeath = now.plusMinutes(minutes);
	}

	private void goOnline(FakePlayerProfile profile, LocalDateTime now, WindowInstance window, boolean fromBreak) {
		if (profile.online) {
			return;
		}
		profile.online = true;
		profile.inBreak = false;
		profile.breakUntil = null;
		profile.nextLogin = null;
		if (window != null) {
			profile.currentWindowEnd = window.end();
		}
		scheduleNextBreak(profile, now, window);
		scheduleNextDeath(profile, now);
		sendAddSnapshot();
		sendJoinMessage(profile);
		ExampleMod.refreshTabListForAllRealPlayers(this.server);
	}

	private void goOffline(FakePlayerProfile profile, LocalDateTime now) {
		if (!profile.online && !profile.inBreak) {
			return;
		}
		boolean wasOnline = profile.online;
		profile.online = false;
		profile.inBreak = false;
		profile.breakUntil = null;
		profile.nextLogin = null;
		profile.nextBreakStart = null;
		profile.currentWindowEnd = null;
		profile.nextDeath = null;
		if (wasOnline) {
			sendRemove(profile);
			sendLeaveMessage(profile);
			ExampleMod.refreshTabListForAllRealPlayers(this.server);
		}
	}

	private void startBreak(FakePlayerProfile profile, LocalDateTime now, WindowInstance window) {
		int durationMinutes = ThreadLocalRandom.current().nextInt(globalConfig.minBreakMinutes, globalConfig.maxBreakMinutes + 1);
		LocalDateTime plannedEnd = now.plusMinutes(durationMinutes);
		if (window != null && plannedEnd.isAfter(window.end())) {
			// Break doesn't fit; stay online until window end.
			profile.nextBreakStart = null;
			return;
		}

		profile.inBreak = true;
		profile.online = false;
		profile.breakUntil = plannedEnd;
		profile.nextLogin = plannedEnd;
		profile.nextBreakStart = null;
		profile.nextDeath = null;
		sendRemove(profile);
		sendLeaveMessage(profile);
		ExampleMod.refreshTabListForAllRealPlayers(this.server);
	}

	private void endBreak(FakePlayerProfile profile, LocalDateTime now, WindowInstance window) {
		profile.inBreak = false;
		profile.breakUntil = null;
		profile.nextLogin = null;
		goOnline(profile, now, window, true);
	}

	private void deliverPendingChats(LocalDateTime now) {
		if (pendingChats.isEmpty() || this.server == null) {
			return;
		}

		pendingChats.removeIf(chat -> {
			if (!now.isBefore(chat.when) && chat.speaker.online()) {
				Component line = Component.literal("<" + chat.speaker.nameAndId().name() + "> " + chat.message);
				this.server.getPlayerList().broadcastSystemMessage(line, false);
				return true;
			}
			return false;
		});
	}

	private void sendAddSnapshot() {
		if (this.server == null) {
			return;
		}
		var entries = ExampleMod.getFakeTabEntries();
		EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions = EnumSet.of(
			ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
			ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
			ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
			ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
			ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME
		);

		for (ServerPlayer viewer : this.server.getPlayerList().getPlayers()) {
			ClientboundPlayerInfoUpdatePacket packet = new ClientboundPlayerInfoUpdatePacket(
				actions,
				Collections.emptyList()
			);
			var acc = (com.example.mixin.ClientboundPlayerInfoUpdatePacketAccessor) packet;
			acc.setEntries(entries);
			viewer.connection.send(packet);
		}
	}

	private LocalDateTime currentEasternTime() {
		return LocalDateTime.now(EASTERN);
	}

	private void sendAddSnapshotTo(ServerPlayer player) {
		if (this.server == null) {
			return;
		}
		var entries = ExampleMod.getFakeTabEntries();
		EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions = EnumSet.of(
			ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
			ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
			ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
			ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY,
			ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME
		);

		ClientboundPlayerInfoUpdatePacket packet = new ClientboundPlayerInfoUpdatePacket(
			actions,
			Collections.emptyList()
		);
		var acc = (com.example.mixin.ClientboundPlayerInfoUpdatePacketAccessor) packet;
		acc.setEntries(entries);
		player.connection.send(packet);
	}

	private void sendRemove(FakePlayerProfile profile) {
		if (this.server == null) {
			return;
		}
		ClientboundPlayerInfoRemovePacket packet = new ClientboundPlayerInfoRemovePacket(Collections.singletonList(profile.nameAndId().id()));
		this.server.getPlayerList().broadcastAll(packet);
	}

	private void sendJoinMessage(FakePlayerProfile profile) {
		if (this.server == null) {
			return;
		}
		Component msg = Component.literal(profile.nameAndId().name() + " joined the game").withStyle(ChatFormatting.YELLOW);
		this.server.getPlayerList().broadcastSystemMessage(msg, false);
	}

	private void sendLeaveMessage(FakePlayerProfile profile) {
		if (this.server == null) {
			return;
		}
		Component msg = Component.literal(profile.nameAndId().name() + " left the game").withStyle(ChatFormatting.YELLOW);
		this.server.getPlayerList().broadcastSystemMessage(msg, false);
	}

	private void sendDeathMessage(FakePlayerProfile profile) {
		if (this.server == null) {
			return;
		}
		String base = DEATH_MESSAGES.get(random.nextInt(DEATH_MESSAGES.size()));
		String resolved = String.format(base, profile.nameAndId().name());
		Component msg = Component.literal(resolved);
		this.server.getPlayerList().broadcastSystemMessage(msg, false);
	}

	private void maybeTriggerDeath(FakePlayerProfile profile, LocalDateTime now) {
		if (this.server == null || !profile.online || profile.nextDeath == null) {
			return;
		}
		if (now.isBefore(profile.nextDeath)) {
			return;
		}
		sendDeathMessage(profile);
		scheduleNextDeath(profile, now);
	}

	private record PendingChat(LocalDateTime when, FakePlayerProfile speaker, String message) {}

	private record ActiveWindow(LocalTime start, LocalTime end) {}

	private record WindowInstance(LocalDateTime start, LocalDateTime end) {}

	private record SkinTextures(String value, String signature) {}

	private static class FakePlayerProfile {
		private final NameAndId nameAndId;
		private final GameProfile profile;
		private final ClientboundPlayerInfoUpdatePacket.Entry entry;
		private final List<ActiveWindow> activeWindows;
		private boolean online = false;
		private boolean inBreak = false;
		private LocalDateTime breakUntil = null;
		private LocalDateTime nextLogin = null;
		private LocalDateTime nextBreakStart = null;
		private LocalDateTime currentWindowEnd = null;
		private LocalDateTime nextDeath = null;

		FakePlayerProfile(NameAndId nameAndId, GameProfile profile, ClientboundPlayerInfoUpdatePacket.Entry entry, List<ActiveWindow> activeWindows) {
			this.nameAndId = nameAndId;
			this.profile = profile;
			this.entry = entry;
			this.activeWindows = activeWindows;
		}

		NameAndId nameAndId() {
			return nameAndId;
		}

		ClientboundPlayerInfoUpdatePacket.Entry entry() {
			return entry;
		}

		boolean online() {
			return online;
		}

		void resetState() {
			resetScheduleState();
			this.online = false;
		}

		void resetScheduleState() {
			this.inBreak = false;
			this.breakUntil = null;
			this.nextLogin = null;
			this.nextBreakStart = null;
			this.currentWindowEnd = null;
			this.nextDeath = null;
		}
	}

	public record FakePlayerStatus(
		String name,
		UUID id,
		boolean online,
		boolean inBreak,
		boolean inActiveWindow,
		LocalDateTime nextBreakStart,
		LocalDateTime breakUntil,
		LocalDateTime nextLogin,
		LocalDateTime currentWindowEnd,
		LocalDateTime nextDeath
	) {}
}
