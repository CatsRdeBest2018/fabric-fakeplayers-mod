package com.example.config;

import com.example.ExampleMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Loads and persists fake player configuration to the Fabric config directory.
 */
public final class FakePlayerConfigManager {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path CONFIG_PATH = FabricLoader.getInstance()
		.getConfigDir()
		.resolve(ExampleMod.MOD_ID + "-fakeplayers.json");

	private static FakePlayerConfig CONFIG;

	private FakePlayerConfigManager() {}

	public static FakePlayerConfig getConfig() {
		return CONFIG;
	}

	public static void loadOrCreate(MinecraftServer server) {
		FakePlayerConfig loaded = readConfig();
		if (loaded == null) {
			loaded = FakePlayerConfig.createDefault();
			normalize(loaded);
			save(loaded);
		} else {
			normalize(loaded);
		}
		CONFIG = loaded;
		if (server != null) {
			ExampleMod.getScheduler().applyConfig(server, CONFIG);
		}
	}

	public static boolean reload(MinecraftServer server) {
		FakePlayerConfig loaded = readConfig();
		if (loaded == null) {
			ExampleMod.LOGGER.warn("[FakePlayers] Reload failed: could not read config, keeping existing configuration.");
			return false;
		}
		normalize(loaded);
		CONFIG = loaded;
		if (server != null) {
			ExampleMod.getScheduler().applyConfig(server, CONFIG);
		}
		return true;
	}

	private static FakePlayerConfig readConfig() {
		if (!Files.exists(CONFIG_PATH)) {
			return null;
		}
		try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
			return GSON.fromJson(reader, FakePlayerConfig.class);
		} catch (Exception e) {
			ExampleMod.LOGGER.error("[FakePlayers] Failed to read config from {}", CONFIG_PATH, e);
			return null;
		}
	}

	public static void normalize(FakePlayerConfig config) {
		if (config == null) {
			return;
		}
		if (config.global == null) {
			config.global = new GlobalConfig();
		}
		if (config.global.minBreakMinutes < 0) {
			config.global.minBreakMinutes = 0;
		}
		if (config.global.maxBreakMinutes < 0) {
			config.global.maxBreakMinutes = 0;
		}
		if (config.global.minBreakMinutes > config.global.maxBreakMinutes) {
			int temp = config.global.minBreakMinutes;
			config.global.minBreakMinutes = config.global.maxBreakMinutes;
			config.global.maxBreakMinutes = temp;
		}

		if (config.global.minIntervalMinutes < 0) {
			config.global.minIntervalMinutes = 0;
		}
		if (config.global.maxIntervalMinutes < 0) {
			config.global.maxIntervalMinutes = 0;
		}
		if (config.global.minIntervalMinutes > config.global.maxIntervalMinutes) {
			int temp = config.global.minIntervalMinutes;
			config.global.minIntervalMinutes = config.global.maxIntervalMinutes;
			config.global.maxIntervalMinutes = temp;
		}
		if (config.global.minDeathMinutes < 0) {
			config.global.minDeathMinutes = 0;
		}
		if (config.global.maxDeathMinutes < 0) {
			config.global.maxDeathMinutes = 0;
		}
		if (config.global.minDeathMinutes > config.global.maxDeathMinutes) {
			int temp = config.global.minDeathMinutes;
			config.global.minDeathMinutes = config.global.maxDeathMinutes;
			config.global.maxDeathMinutes = temp;
		}

		if (config.bots == null) {
			config.bots = new ArrayList<>();
		}

		for (BotConfig bot : config.bots) {
			if (bot == null) {
				continue;
			}
			bot.normalize();
		}
	}

	private static void save(FakePlayerConfig config) {
		try {
			Files.createDirectories(CONFIG_PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8)) {
				GSON.toJson(config, writer);
			}
		} catch (IOException e) {
			ExampleMod.LOGGER.error("[FakePlayers] Failed to save config to {}", CONFIG_PATH, e);
		}
	}
}
