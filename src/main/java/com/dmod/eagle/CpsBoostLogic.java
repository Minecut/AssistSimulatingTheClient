package com.dmod.eagle;

import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;

/**
 * Right-click pacing for {@link EagleLogic}'s module.
 *
 * <p>Vanilla cannot hold a use rate above 4 ticks per use, i.e. a flat 5 CPS: {@code doItemUse()}
 * stamps {@code itemUseCooldown = 4}, {@code MinecraftClient.tick()} decrements it once per tick, and
 * {@code handleInputEvents()} only re-fires the <em>held</em> path while that counter sits at zero.
 *
 * <p>Rather than calling {@code doItemUse()} ourselves - it is private, and doing so would bypass the
 * crosshair refresh and the {@code isUsingItem} guard that vanilla applies - this class drives the
 * counter instead. The mixin hands us the head of {@code MinecraftClient.tick()}, one step before
 * the decrement, and we answer a single question: should the held use fire this tick?
 *
 * <ul>
 *   <li><b>yes</b> - the counter is forced to 0, so vanilla fires on its own, with a fresh crosshair
 *       target and all of its normal checks.</li>
 *   <li><b>no</b> - the counter is held at 2 or above, so after the decrement it can never be 0 and
 *       vanilla stays quiet.</li>
 * </ul>
 *
 * <p>Two rules keep this honest. The player must already be holding the right mouse button -
 * {@code useKey.isPressed()} is the raw key state and nothing in this mod ever touches it - so the
 * booster can never place a block on its own. And the schedule only advances while that hold lasts,
 * so a tap stays a single click.
 */
public final class CpsBoostLogic {

    private static final double TICK_MS = 50.0D;

    /** Smallest counter value that still reads as non-zero after the tick's decrement. */
    public static final int MIN_BLOCKING_COOLDOWN = 2;

    private static double accumulatorMs = 0.0D;
    private static double nextGapMs = 0.0D;
    private static boolean pacing = false;

    private CpsBoostLogic() {
    }

    /** True while the booster is riding a held right-click this tick. Drives the HUD. */
    public static boolean isPacing() {
        return pacing;
    }

    public static void reset() {
        accumulatorMs = 0.0D;
        nextGapMs = 0.0D;
        pacing = false;
    }

    /**
     * Called at the head of every client tick, before the game decrements the use counter.
     *
     * @return {@code true} when the held use should be allowed to fire this tick
     */
    public static boolean shouldFireThisTick(MinecraftClient mc) {
        pacing = false;

        EagleConfig cfg = EagleConfig.get();

        if (!cfg.enabled || !cfg.cpsBoostEnabled) {
            reset();
            return false;
        }

        if (mc == null
                || mc.player == null
                || mc.world == null
                || mc.options == null
                || mc.currentScreen != null) {
            reset();
            return false;
        }

        ClientPlayerEntity player = mc.player;

        if (!player.isAlive() || player.isSpectator()) {
            reset();
            return false;
        }

        // Vanilla refuses the held path while an item is in use; do not fight it.
        if (player.isUsingItem()) {
            reset();
            return false;
        }

        // The only thing that can start a click is the player's own finger.
        if (!mc.options.useKey.isPressed()) {
            reset();
            return false;
        }

        if (cfg.cpsRequireBlock && !isHoldingBlock(player)) {
            reset();
            return false;
        }

        pacing = true;

        accumulatorMs += TICK_MS;

        if (nextGapMs <= 0.0D) {
            nextGapMs = pickGap(cfg);
        }

        if (accumulatorMs >= nextGapMs) {
            accumulatorMs -= nextGapMs;
            nextGapMs = pickGap(cfg);
            return true;
        }

        // Not this tick. Keep whatever is left over so the long run average still lands inside the
        // configured band - the per-tick grid alone would snap the rate to 20/2 or 20/3 CPS.
        return false;
    }

    /** A fresh random gap in the {@code cpsMin .. cpsMax} band, in milliseconds. */
    private static double pickGap(EagleConfig cfg) {
        double low = Math.min(cfg.cpsMin, cfg.cpsMax);
        double high = Math.max(cfg.cpsMin, cfg.cpsMax);
        double shortest = 1000.0D / high;
        double longest = 1000.0D / low;
        return shortest + ThreadLocalRandom.current().nextDouble() * (longest - shortest);
    }

    private static boolean isHoldingBlock(ClientPlayerEntity player) {
        ItemStack stack = player.getMainHandStack();
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem;
    }
}
