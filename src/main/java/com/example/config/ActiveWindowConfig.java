package com.example.config;

/**
 * Time range (local server time) when a fake player should be considered active.
 */
public class ActiveWindowConfig {
	public String startTime;
	public String endTime;

	public static ActiveWindowConfig of(String startTime, String endTime) {
		ActiveWindowConfig config = new ActiveWindowConfig();
		config.startTime = startTime;
		config.endTime = endTime;
		return config;
	}
}
