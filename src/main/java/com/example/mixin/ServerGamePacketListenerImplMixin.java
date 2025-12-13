package com.example.mixin;

import com.example.ExampleMod;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerCommonPacketListenerImpl.class)
public class ServerGamePacketListenerImplMixin {
	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
	private void logPlayerInfoPackets(Packet<?> packet, CallbackInfo ci) {
		if (packet instanceof ClientboundPlayerInfoUpdatePacket info) {
			var acc = (ClientboundPlayerInfoUpdatePacketAccessor) info;
			int size = acc.getEntries().size();
			ExampleMod.LOGGER.info("[FakePlayers] Outgoing PlayerInfo packet entries: {}", size);
			for (var entry : acc.getEntries()) {
				ExampleMod.LOGGER.info("[FakePlayers]  -> {} ({})", entry.profile().name(), entry.profile().id());
			}
		}
	}
}
