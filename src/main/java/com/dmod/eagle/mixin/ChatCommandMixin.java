package com.dmod.eagle.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.dmod.eagle.EagleCommands;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;

/**
 * Intercepts the chat box before a message becomes a packet.
 *
 * <p>{@code ClientPlayNetworkHandler#sendChatMessage(String)} is the single funnel every plain chat
 * message goes through - slash commands take the sibling {@code sendChatCommand} path and are left
 * untouched. Cancelling here means the text never leaves the client, which is exactly what a client
 * side command is: no packet, no server round trip, nothing for anyone else to see.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ChatCommandMixin {

    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
    private void eagle$clientCommand(String content, CallbackInfo ci) {
        if (EagleCommands.handle(MinecraftClient.getInstance(), content)) {
            ci.cancel();
        }
    }
}
