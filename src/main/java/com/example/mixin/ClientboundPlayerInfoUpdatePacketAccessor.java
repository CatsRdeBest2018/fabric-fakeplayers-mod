package com.example.mixin;

import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(ClientboundPlayerInfoUpdatePacket.class)
public interface ClientboundPlayerInfoUpdatePacketAccessor {
	@Accessor("entries")
	@Mutable
	void setEntries(List<ClientboundPlayerInfoUpdatePacket.Entry> entries);

	@Accessor("entries")
	List<ClientboundPlayerInfoUpdatePacket.Entry> getEntries();
}
