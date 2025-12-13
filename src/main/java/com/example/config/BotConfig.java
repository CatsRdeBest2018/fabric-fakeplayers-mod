package com.example.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Single bot definition: identity, enable flag, and active time windows.
 */
public class BotConfig {
	public String name;
	public String uuid;
	public boolean enabled = true;
	public String skinUuid;
	public String textureValue;
	public String textureSignature;
	public List<ActiveWindowConfig> activeWindows = new ArrayList<>();

	/**
	 * Normalize fields, ensuring UUID and window list are populated.
	 */
	public void normalize() {
		if (name == null || name.isBlank()) {
			enabled = false;
			return;
		}

		if (uuid == null || uuid.isBlank()) {
			this.uuid = java.util.UUID
				.nameUUIDFromBytes(("fakebot:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8))
				.toString();
		}
		if (activeWindows == null) {
			activeWindows = new ArrayList<>();
		}
	}
}
