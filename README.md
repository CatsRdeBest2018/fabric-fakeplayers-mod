# Always Online (Fabric)

Server-only Fabric mod for 1.21.10 that advertises fake players:
- Adds five generated names to the server list ping response.
- Appends those same names to every `ClientboundPlayerInfoUpdatePacket`, so they appear in the in-game tab list and count.
- No actual entities are created; it only touches status and tab packets.

### Building
Run `./gradlew build` to produce the mod jar in `build/libs/`.

### Tweaking the names/count
The constants and name pools live in `src/main/java/com/example/ExampleMod.java` (`FAKE_PLAYER_COUNT`, `randomName`). Adjust them and rebuild if you want different names or a different number of always-online players.
