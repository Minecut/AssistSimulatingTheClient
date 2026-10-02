package com.dmod.eagle.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.dmod.eagle.AimAssistLogic;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;

/**
 * Injects the aim assist into the mouse movement.
 *
 * <p>{@code Mouse#tick()} is called once per rendered frame from {@code MinecraftClient#render}, and it
 * does exactly three things in order: hand {@code cursorDeltaX}/{@code cursorDeltaY} to
 * {@code updateMouse}, let the player's own rotation be applied, then zero both fields. Adding our
 * contribution at the head of {@code updateMouse} therefore lands inside that window: it is consumed
 * once, by the same expression that consumes the real mouse movement, and the result is a camera that
 * actually turns.
 */
@Mixin(Mouse.class)
public abstract class MouseMixin {

    @Shadow
    private double cursorDeltaX;

    @Shadow
    private double cursorDeltaY;

    @Inject(method = "updateMouse", at = @At("HEAD"))
    private void eagle$aimAssist(double time, CallbackInfo ci) {
        double[] correction = AimAssistLogic.mouseCorrection(MinecraftClient.getInstance());

        if (correction == null) {
            return;
        }

        this.cursorDeltaX += correction[0];
        this.cursorDeltaY += correction[1];
    }
}
