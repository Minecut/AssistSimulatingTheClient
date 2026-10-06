package com.dmod.eagle.gui;

import net.minecraft.client.gui.DrawContext;

/** Colours and the handful of drawing primitives the click GUI needs. */
public final class GuiTheme {

    // ------------------------------------------------------------------ palette

    /** Panel body. */
    public static final int PANEL = 0xD4141418;
    /** Panel header strip. */
    public static final int HEADER = 0xF01C1C22;
    /** Drop shadow behind a panel. */
    public static final int SHADOW = 0x66000000;
    /** Hairline border. */
    public static final int BORDER = 0x22FFFFFF;
    /** Row hover wash. */
    public static final int HOVER = 0x18FFFFFF;
    /** Slider track and the un-filled part of a number row. */
    public static final int TRACK = 0x50000000;
    /** Divider under the header. */
    public static final int DIVIDER = 0x18FFFFFF;

    public static final int TEXT_TITLE = 0xFFFFFFFF;
    /** Module name when the module is on. */
    public static final int TEXT_ON = 0xFFF2F2F6;
    /** Module name when the module is off. */
    public static final int TEXT_OFF = 0xFF7C7C88;
    public static final int TEXT_SETTING = 0xFFB0B0BC;
    public static final int TEXT_VALUE = 0xFF7FD8FF;
    public static final int TEXT_KEY = 0xFF6A6A76;
    public static final int TEXT_FOOTER = 0xFF6A6A76;

    /** Per-category accent, indexed by category order. */
    public static final int[] ACCENTS = {
            0xFF4CA8FF, // Movement
            0xFFFF4C4C, // Combat
            0xFF54E08A, // Player
            0xFFB44CFF, // Render
    };

    // ------------------------------------------------------------------ metrics

    public static final int PANEL_WIDTH = 118;
    public static final int HEADER_HEIGHT = 18;
    public static final int MODULE_HEIGHT = 16;
    public static final int SETTING_HEIGHT = 13;
    public static final int PANEL_GAP = 6;
    public static final int PADDING = 4;
    public static final int CORNER = 4;

    private GuiTheme() {
    }

    // ------------------------------------------------------------------ primitives

    /**
     * A rectangle with rounded corners.
     *
     * <p>There is no shader behind this: the body is one flat quad and each corner is approximated by
     * {@code radius} one-pixel-tall rows whose ends follow a circle. At the radii used here that is
     * indistinguishable from a real rounded rect and costs a handful of quads.
     */
    public static void roundedRect(DrawContext context, int x, int y, int w, int h, int radius, int color) {
        if (w <= 0 || h <= 0) {
            return;
        }

        int r = Math.max(0, Math.min(radius, Math.min(w, h) / 2));

        if (r == 0) {
            context.fill(x, y, x + w, y + h, color);
            return;
        }

        context.fill(x, y + r, x + w, y + h - r, color);

        for (int row = 0; row < r; row++) {
            int inset = cornerInset(r, row);
            context.fill(x + inset, y + row, x + w - inset, y + row + 1, color);
            context.fill(x + inset, y + h - 1 - row, x + w - inset, y + h - row, color);
        }
    }

    /** Same as {@link #roundedRect} but with an alpha multiplier, used for the open animation. */
    public static void roundedRectFaded(DrawContext context, int x, int y, int w, int h, int radius,
                                        int color, float alpha) {
        roundedRect(context, x, y, w, h, radius, withAlpha(color, alpha));
    }

    private static int cornerInset(int radius, int row) {
        double dy = radius - row - 0.5D;
        double dx = Math.sqrt(Math.max(0.0D, (double) radius * radius - dy * dy));
        return (int) Math.round(radius - dx);
    }

    /** Scales a colour's alpha by {@code factor}, clamped to 0..1. */
    public static int withAlpha(int color, float factor) {
        float f = Math.max(0.0F, Math.min(1.0F, factor));
        int alpha = (int) (((color >>> 24) & 0xFF) * f);
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    /** Linear blend between two ARGB colours. */
    public static int lerpColor(int from, int to, float t) {
        float f = Math.max(0.0F, Math.min(1.0F, t));
        int a = lerp((from >>> 24) & 0xFF, (to >>> 24) & 0xFF, f);
        int r = lerp((from >>> 16) & 0xFF, (to >>> 16) & 0xFF, f);
        int g = lerp((from >>> 8) & 0xFF, (to >>> 8) & 0xFF, f);
        int b = lerp(from & 0xFF, to & 0xFF, f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int lerp(int from, int to, float t) {
        return Math.round(from + (to - from) * t);
    }

    /** Exponentially approaches {@code target}, framerate independently. */
    public static float approach(float current, float target, float speed, float dt) {
        float factor = 1.0F - (float) Math.pow(1.0F - Math.max(0.0F, Math.min(1.0F, speed)), dt * 20.0F);
        return current + (target - current) * factor;
    }

    /** Ease-out cubic, for the panel slide-in. */
    public static float easeOut(float t) {
        float f = Math.max(0.0F, Math.min(1.0F, t));
        float inv = 1.0F - f;
        return 1.0F - inv * inv * inv;
    }
}
