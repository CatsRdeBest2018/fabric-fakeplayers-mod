package com.example.mixin;

import com.example.ExampleMod;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerStatusMixin {
	@Shadow
	public abstract PlayerList getPlayerList();

	@Inject(method = "buildServerStatus", at = @At("RETURN"), cancellable = true)
	private void injectFakePlayers(CallbackInfoReturnable<ServerStatus> cir) {
		ServerStatus status = cir.getReturnValue();
		if (status == null) {
			return;
		}

		ServerStatus updated = ExampleMod.withFakePlayers(status, this.getPlayerList());
		cir.setReturnValue(updated);
	}
}
