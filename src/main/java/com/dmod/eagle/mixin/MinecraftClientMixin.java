package com.dmod.eagle.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.dmod.eagle.CpsBoostLogic;

import net.minecraft.client.MinecraftClient;

/**
 * Paces the held right-click.
 *
 * <p>The injection sits at the very head of {@code MinecraftClient.tick()}, which is the last moment
 * before the game runs {@code if (itemUseCooldown > 0) itemUseCooldown--}. Whatever value we leave
 * here decides whether the held-use path inside {@code handleInputEvents()}, later in the same tick,
 * finds a zero and fires. See {@link CpsBoostLogic} for the schedule itself.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    @Shadow
    private int itemUseCooldown;

    @Inject(method = "tick", at = @At("HEAD"))
    private void eagle$paceHeldRightClick(CallbackInfo ci) {
        MinecraftClient mc = (MinecraftClient) (Object) this;

        if (CpsBoostLogic.shouldFireThisTick(mc)) {
            // Let the vanilla path fire this tick, with its own crosshair target and guards.
            this.itemUseCooldown = 0;
            return;
        }

        if (CpsBoostLogic.isPacing()) {
            // Hold the counter above zero so this tick's decrement cannot reach it.
            this.itemUseCooldown = Math.max(this.itemUseCooldown, CpsBoostLogic.MIN_BLOCKING_COOLDOWN);
        }

        // Not pacing at all: leave the field completely alone and let vanilla behave normally.
    }
}
