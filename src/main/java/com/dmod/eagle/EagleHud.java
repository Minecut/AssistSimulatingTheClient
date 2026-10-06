package com.dmod.eagle;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Top left readout so the state is visible without opening a menu.
 *
 * <p>The segments are laid out with wrapping rather than as one fixed line: seven modules plus their
 * activity tags overflow the screen at low GUI scales otherwise.
 */
public final class EagleHud {

    private static final int MARGIN = 4;
    private static final int LINE_HEIGHT = 10;
    private static final String GAP = "   ";

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

        boolean eagleOn = cfg.enabled;
        boolean cpsOn = cfg.enabled && cfg.cpsBoostEnabled;

        List<Text> segments = new ArrayList<>(7);
        segments.add(segment("Eagle", eagleOn, eagleOn && EagleLogic.isEngaged() ? "sneak" : null));
        segments.add(segment("CPS", cpsOn, cpsOn && CpsBoostLogic.isPacing() ? "hold" : null));
        segments.add(segment("Pad", cfg.safePadEnabled,
                cfg.safePadEnabled && SafePadLogic.isPlacing() ? "place" : null));
        segments.add(segment("Aim", cfg.aimEnabled,
                cfg.aimEnabled && AimAssistLogic.isAiming() ? "lock" : null));
        segments.add(segment("Inv", cfg.invChestEnabled,
                cfg.invChestEnabled && InvChestLogic.isTaking() ? "take" : null));
        segments.add(segment("ESP", cfg.espEnabled,
                cfg.espEnabled && EspLogic.isActive() ? Integer.toString(EspLogic.trackedCount()) : null));
        segments.add(segment("BI", cfg.blockInEnabled,
                cfg.blockInEnabled && BlockInLogic.isWorking() ? "work" : null));

        int maxWidth = Math.max(40, mc.getWindow().getScaledWidth() - MARGIN * 2);
        int x = MARGIN;
        int y = MARGIN;

        for (Text segment : segments) {
            int width = mc.textRenderer.getWidth(segment);

            if (x > MARGIN && x + width > MARGIN + maxWidth) {
                y += LINE_HEIGHT;
                x = MARGIN;
            }

            context.drawText(mc.textRenderer, segment, x, y, 0xFFFFFF, true);
            x += width;
        }
    }

    private static Text segment(String name, boolean on, String tag) {
        MutableText text = Text.literal(name + " ")
                .formatted(on ? Formatting.AQUA : Formatting.DARK_GRAY)
                .append(Text.literal(on ? "ON" : "OFF")
                        .formatted(on ? Formatting.GREEN : Formatting.RED));

        if (tag != null) {
            text.append(Text.literal(" " + tag).formatted(Formatting.YELLOW));
        }

        return text.append(Text.literal(GAP));
    }
}
