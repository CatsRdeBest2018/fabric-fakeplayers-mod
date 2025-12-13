package com.example.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Root configuration for the fake player system. Controls global behavior and the list of bots.
 */
public class FakePlayerConfig {
	public GlobalConfig global = new GlobalConfig();
	public List<BotConfig> bots = new ArrayList<>();

	/**
	 * Build a default configuration with two sample bots.
	 */
	public static FakePlayerConfig createDefault() {
		FakePlayerConfig config = new FakePlayerConfig();
		config.global = new GlobalConfig();
		config.bots = new java.util.ArrayList<>();

		BotConfig steve = new BotConfig();
		steve.name = "Steve";
		steve.enabled = true;
		steve.activeWindows = java.util.List.of(
			ActiveWindowConfig.of("15:00", "18:30")
		);

		BotConfig alex = new BotConfig();
		alex.name = "Alex";
		alex.enabled = true;
		alex.activeWindows = java.util.List.of(
			ActiveWindowConfig.of("00:17", "00:25"),
			ActiveWindowConfig.of("19:30", "23:00")
		);

		config.bots.add(steve);
		config.bots.add(alex);
		return config;
	}
}
