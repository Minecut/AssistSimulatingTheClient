package com.dmod.eagle.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.dmod.eagle.gui.ScoreboardPanel;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;

/**
 * Stands the vanilla sidebar down while the panel replaces it.
 *
 * <p>Both overloads are targeted. The {@code RenderTickCounter} one is the entry point called from the
 * HUD's render pass and the {@code ScoreboardObjective} one does the actual drawing; cancelling either
 * alone leaves a vanilla sidebar behind. {@code require = 0} because this is a cosmetic replacement -
 * a renamed overload should cost the styling, not the launch.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

    @Inject(
            method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private void eagle$hideSidebar(CallbackInfo ci) {
        if (ScoreboardPanel.shouldReplace(MinecraftClient.getInstance())) {
            ci.cancel();
        }
    }

    @Inject(
            method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private void eagle$hideSidebarForObjective(CallbackInfo ci) {
        if (ScoreboardPanel.shouldReplace(MinecraftClient.getInstance())) {
            ci.cancel();
        }
    }
}
