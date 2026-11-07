package com.dmod.eagle.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.dmod.eagle.gui.TabPanel;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.PlayerListHud;

/**
 * Hides the vanilla tab list while the island is showing the same thing.
 *
 * <p>{@code setVisible(false)} does not work for this: {@code render} goes straight into
 * {@code collectPlayerEntries} without ever consulting the visible flag, so cancelling the render
 * itself is the only lever. The gate is the tab key being held in a world we can draw in, which is
 * exactly when the replacement panel is up - the list is never simply gone.
 */
@Mixin(PlayerListHud.class)
public abstract class PlayerListHudMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void eagle$hideVanillaList(CallbackInfo ci) {
        if (TabPanel.isOpen(MinecraftClient.getInstance())) {
            ci.cancel();
        }
    }
}
