package com.example.mixin;

import com.example.ExampleMod;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.EnumSet;
import java.util.List;

@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public abstract class ClientboundPlayerInfoUpdatePacketMixin {
	@Shadow(remap = true, aliases = {"entries", "c"})
	@org.spongepowered.asm.mixin.Final
	private List<ClientboundPlayerInfoUpdatePacket.Entry> entries; // backing entries list
	@Inject(method = "<init>(Ljava/util/EnumSet;Ljava/util/Collection;)V", at = @At("TAIL"))
	private void addFakePlayers(EnumSet<ClientboundPlayerInfoUpdatePacket.Action> actions, java.util.Collection<?> players, CallbackInfo ci) {
		if (actions.contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) {
			appendFakeEntries();
		}
	}

	@Inject(method = "<init>(Lnet/minecraft/network/protocol/game/ClientboundPlayerInfoUpdatePacket$Action;Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("TAIL"))
	private void addFakePlayersSingle(ClientboundPlayerInfoUpdatePacket.Action action, net.minecraft.server.level.ServerPlayer player, CallbackInfo ci) {
		if (action == ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER) {
			appendFakeEntries();
		}
	}

	private void appendFakeEntries() {
		List<ClientboundPlayerInfoUpdatePacket.Entry> existing = this.entries;
		for (ClientboundPlayerInfoUpdatePacket.Entry entry : ExampleMod.getFakeTabEntries()) {
			boolean seen = existing.stream().anyMatch(e -> e.profile().id().equals(entry.profile().id()));
			if (!seen) {
				existing.add(entry);
			}
		}
	}
}
