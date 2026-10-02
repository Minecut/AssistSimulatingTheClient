package com.dmod.eagle;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/** Tiny top left readout so the state is visible without opening a menu. */
public final class EagleHud {

    private EagleHud() {
    }

    public static void render(DrawContext context) {
        EagleConfig cfg = EagleConfig.get();

        if (!cfg.showHud) {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc.player == null || mc.options == null || mc.options.hudHidden) {
            return;
        }

        MutableText text = Text.literal("Eagle ")
                .formatted(cfg.enabled ? Formatting.AQUA : Formatting.DARK_GRAY)
                .append(Text.literal(cfg.enabled ? "ON" : "OFF")
                        .formatted(cfg.enabled ? Formatting.GREEN : Formatting.RED));

        if (cfg.enabled && EagleLogic.isEngaged()) {
            text.append(Text.literal(" [sneak]").formatted(Formatting.YELLOW));
        }

        boolean cpsOn = cfg.enabled && cfg.cpsBoostEnabled;

        text.append(Text.literal("  CPS ").formatted(cpsOn ? Formatting.AQUA : Formatting.DARK_GRAY))
                .append(Text.literal(cpsOn ? "ON" : "OFF")
                        .formatted(cpsOn ? Formatting.GREEN : Formatting.RED));

        if (cpsOn && CpsBoostLogic.isPacing()) {
            text.append(Text.literal(" [hold]").formatted(Formatting.YELLOW));
        }

        text.append(Text.literal("  Pad ").formatted(cfg.safePadEnabled ? Formatting.AQUA : Formatting.DARK_GRAY))
                .append(Text.literal(cfg.safePadEnabled ? "ON" : "OFF")
                        .formatted(cfg.safePadEnabled ? Formatting.GREEN : Formatting.RED));

        if (cfg.safePadEnabled && SafePadLogic.isPlacing()) {
            text.append(Text.literal(" [place]").formatted(Formatting.YELLOW));
        }

        text.append(Text.literal("  Aim ").formatted(cfg.aimEnabled ? Formatting.AQUA : Formatting.DARK_GRAY))
                .append(Text.literal(cfg.aimEnabled ? "ON" : "OFF")
                        .formatted(cfg.aimEnabled ? Formatting.GREEN : Formatting.RED));

        if (cfg.aimEnabled && AimAssistLogic.isAiming()) {
            text.append(Text.literal(" [lock]").formatted(Formatting.YELLOW));
        }

        context.drawText(mc.textRenderer, text, 4, 4, 0xFFFFFF, true);
    }
}
