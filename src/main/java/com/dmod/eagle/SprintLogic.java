package com.dmod.eagle;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * Sprint whenever the player is holding forward.
 *
 * <p>On by default and toggled from the click GUI's Movement category; there is no keybind for it. It
 * is a convenience, not a cheat toggle: it only ever asks for the same state the player could reach by
 * double-tapping the forward key, and it is re-asked every tick because vanilla drops the state on its
 * own whenever it decides the player should not be sprinting.
 *
 * <p>Two states are deliberately left alone. Sneaking wins, because forcing sprint through it would
 * fight both the player's own input and Eagle's edge protection, which works by sneaking. Using an
 * item is skipped too, since bow and food both require standing still being irrelevant - sprinting
 * mid-use is simply an invalid state that would be corrected a tick later anyway.
 */
public final class SprintLogic {

    private SprintLogic() {
    }

    public static void onEndTick(MinecraftClient mc) {
        if (mc == null || mc.player == null || mc.world == null || mc.options == null) {
            return;
        }

        if (!EagleConfig.get().sprintEnabled) {
            return;
        }

        // Keys keep whatever state they had when a screen opened, so a held forward key would look
        // pressed while the player is not actually walking.
        if (mc.currentScreen != null) {
            return;
        }

        if (!mc.options.forwardKey.isPressed()) {
            return;
        }

        ClientPlayerEntity player = mc.player;

        if (!player.isAlive() || player.isSpectator() || player.isSprinting()) {
            return;
        }

        if (player.isSneaking() || player.isUsingItem()) {
            return;
        }

        player.setSprinting(true);
    }
}
