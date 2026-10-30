package com.dmod.eagle.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.dmod.eagle.RemoteShopLogic;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;

/**
 * Turns a container close into a hide.
 *
 * <p>{@code HandledScreen.close()} is the only path a player takes out of a container window, which
 * makes it the precise place to stand: the server closing the container goes through
 * {@code ClientPlayNetworkHandler} instead and is left alone, so this can never desync against a
 * container the other end already considers shut.
 *
 * <p>Cancelling the method skips both halves of the vanilla close - the
 * {@code CloseHandledScreenC2SPacket} and the screen teardown - so the screen is cleared here instead.
 * {@code setScreen(null)} still runs {@code removed()}, but for a chest or shulker handler that is a
 * no-op, so the handler survives intact for the reopen.
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin {

    @Inject(method = "close", at = @At("HEAD"), cancellable = true)
    private void eagle$keepContainerAlive(CallbackInfo ci) {
        HandledScreen<?> self = (HandledScreen<?>) (Object) this;

        if (!RemoteShopLogic.stash(self)) {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc != null) {
            mc.setScreen(null);
        }

        ci.cancel();
    }
}
