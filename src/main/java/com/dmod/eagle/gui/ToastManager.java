package com.dmod.eagle.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/**
 * A single animated notice in the bottom right corner.
 *
 * <p>Vanilla's own toast system puts its boxes in the top right and has no slide. This one slides in
 * from the right edge, holds, then slides back out and fades, all driven off one timestamp so there
 * is no per-frame state to keep in sync.
 *
 * <p>It has to be drawable in two different places: the in-game HUD, and on top of any screen - the
 * account switch happens on the multiplayer screen, where the HUD is hidden behind it. Two entry
 * points handle that, and the HUD one stands down while a screen is open so the notice is never drawn
 * twice.
 */
public final class ToastManager {

    private static final int WIDTH = 158;
    private static final int HEIGHT = 28;
    private static final int MARGIN = 6;
    private static final int ACCENT_BAR = 3;

    private static final long SLIDE_IN_MS = 260L;
    private static final long HOLD_MS = 2400L;
    private static final long SLIDE_OUT_MS = 380L;
    private static final long TOTAL_MS = SLIDE_IN_MS + HOLD_MS + SLIDE_OUT_MS;

    private static String title = "";
    private static String detail = "";
    private static Text titleText = Text.empty();
    private static Text detailText = Text.empty();
    private static int accent = GuiTheme.ACCENTS[0];
    private static long startedAt;
    private static boolean showing;

    private ToastManager() {
    }

    public static void show(String title, String detail, int accent) {
        ToastManager.title = title;
        ToastManager.detail = detail;
        ToastManager.accent = accent;
        ToastManager.startedAt = System.currentTimeMillis();
        ToastManager.showing = true;

        MinecraftClient mc = MinecraftClient.getInstance();
        int room = WIDTH - ACCENT_BAR - 14;

        ToastManager.titleText = Text.literal(trim(mc, title, room));
        ToastManager.detailText = Text.literal(trim(mc, detail, room));
    }

    public static boolean isShowing() {
        return showing;
    }

    /** In-game: quiet while a screen is up, because the screen hook covers that case. */
    public static void renderHud(DrawContext context) {
        if (MinecraftClient.getInstance().currentScreen != null) {
            return;
        }

        draw(context);
    }

    /** On top of a screen. */
    public static void renderScreen(DrawContext context) {
        draw(context);
    }

    private static void draw(DrawContext context) {
        if (!showing) {
            return;
        }

        long elapsed = System.currentTimeMillis() - startedAt;

        if (elapsed >= TOTAL_MS) {
            showing = false;
            return;
        }

        float progress;

        if (elapsed < SLIDE_IN_MS) {
            progress = elapsed / (float) SLIDE_IN_MS;
        } else if (elapsed < SLIDE_IN_MS + HOLD_MS) {
            progress = 1.0F;
        } else {
            progress = 1.0F - (elapsed - SLIDE_IN_MS - HOLD_MS) / (float) SLIDE_OUT_MS;
        }

        float eased = GuiTheme.easeOut(Math.max(0.0F, Math.min(1.0F, progress)));

        MinecraftClient mc = MinecraftClient.getInstance();
        int screenWidth = mc.getWindow().getScaledWidth();
        int screenHeight = mc.getWindow().getScaledHeight();

        int offset = Math.round((1.0F - eased) * (WIDTH + MARGIN + 6));
        int x = screenWidth - MARGIN - WIDTH + offset;
        int y = screenHeight - MARGIN - HEIGHT;

        GuiTheme.roundedRect(context, x + 2, y + 3, WIDTH, HEIGHT, GuiTheme.CORNER,
                GuiTheme.withAlpha(GuiTheme.SHADOW, eased));
        GuiTheme.roundedRect(context, x, y, WIDTH, HEIGHT, GuiTheme.CORNER,
                GuiTheme.withAlpha(GuiTheme.PANEL, eased));

        // accent bar down the left edge, inset so it cannot poke out of the rounded corners
        context.fill(x + 1, y + GuiTheme.CORNER, x + 1 + ACCENT_BAR, y + HEIGHT - GuiTheme.CORNER,
                GuiTheme.withAlpha(accent, eased));

        int textX = x + ACCENT_BAR + 8;

        context.drawText(mc.textRenderer, titleText, textX, y + 6,
                GuiTheme.withAlpha(accent, eased), true);
        context.drawText(mc.textRenderer, detailText, textX, y + 16,
                GuiTheme.withAlpha(GuiTheme.TEXT_SETTING, eased), true);
    }

    private static String trim(MinecraftClient mc, String text, int maxWidth) {
        if (mc == null || mc.textRenderer == null || text == null) {
            return text == null ? "" : text;
        }

        if (mc.textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }

        String cut = text;

        while (cut.length() > 1 && mc.textRenderer.getWidth(cut + "..") > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }

        return cut + "..";
    }
}
