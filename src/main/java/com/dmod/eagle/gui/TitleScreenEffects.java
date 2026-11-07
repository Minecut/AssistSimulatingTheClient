package com.dmod.eagle.gui;

import java.util.Random;

import com.dmod.eagle.EagleConfig;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * Ambient animation drawn over the main menu.
 *
 * <p>Four layers, back to front: a slow colour wash, a drifting aurora band, a field of rising motes,
 * and a breathing vignette. Everything is expressed as a fraction of the screen and scaled by a
 * seconds-based delta, so the menu looks the same at any resolution or frame rate.
 *
 * <p>This draws on top of the vanilla title screen rather than behind it - replacing the panorama
 * would mean reaching into {@code TitleScreen}'s own render path, and every layer here is deliberately
 * faint enough that the buttons stay perfectly readable through it. The point is atmosphere, not a
 * new background.
 *
 * <p>Cost is one full-screen quad for the wash, four gradient quads for the band and vignette, and
 * three small quads per mote - about 180 draws in total, none of which allocate.
 */
public final class TitleScreenEffects {

    private static final int PARTICLES = 56;
    private static final long SEED = 0xEA61EL;

    private static final float[] PX = new float[PARTICLES];
    private static final float[] PY = new float[PARTICLES];
    private static final float[] SPEED = new float[PARTICLES];
    private static final float[] SIZE = new float[PARTICLES];
    private static final float[] PHASE = new float[PARTICLES];
    private static final int[] TINT = new int[PARTICLES];

    private static boolean seeded;
    private static long lastFrameNanos;
    private static float time;

    /** Smoothed cursor offset in -1..1 on each axis, driving the parallax. */
    private static float lookX;
    private static float lookY;

    /** Smoothed cursor position in pixels, for the trailing glow. */
    private static float glowX = Float.NaN;
    private static float glowY = Float.NaN;

    private TitleScreenEffects() {
    }

    public static void render(DrawContext context, int mouseX, int mouseY) {
        if (!EagleConfig.get().titleEffects) {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc == null || mc.getWindow() == null) {
            return;
        }

        int width = mc.getWindow().getScaledWidth();
        int height = mc.getWindow().getScaledHeight();

        if (width <= 0 || height <= 0) {
            return;
        }

        long now = System.nanoTime();

        float dt = lastFrameNanos == 0L ? 0.016F : Math.min(0.1F, (now - lastFrameNanos) / 1.0E9F);
        lastFrameNanos = now;
        time += dt;

        seed();
        follow(mouseX, mouseY, width, height, dt);

        wash(context, width, height);
        aurora(context, width, height);
        motes(context, width, height, dt);
        vignette(context, width, height);
        cursorGlow(context);
    }

    // ------------------------------------------------------------------ layers

    /**
     * Eases the cursor-derived state towards where the mouse actually is.
     *
     * <p>The smoothing is what makes it read as a camera drifting rather than a layer pinned to the
     * pointer: the effects lag the cursor slightly and settle, which is the difference between
     * parallax and a sticker.
     */
    private static void follow(int mouseX, int mouseY, int width, int height, float dt) {
        float nx = (mouseX / (float) width - 0.5F) * 2.0F;
        float ny = (mouseY / (float) height - 0.5F) * 2.0F;

        lookX = GuiTheme.approach(lookX, Math.max(-1.0F, Math.min(1.0F, nx)), 0.22F, dt);
        lookY = GuiTheme.approach(lookY, Math.max(-1.0F, Math.min(1.0F, ny)), 0.22F, dt);

        if (Float.isNaN(glowX)) {
            glowX = mouseX;
            glowY = mouseY;
        } else {
            glowX = GuiTheme.approach(glowX, mouseX, 0.35F, dt);
            glowY = GuiTheme.approach(glowY, mouseY, 0.35F, dt);
        }
    }

    /** Three concentric circles under the cursor - a soft light that trails it. */
    private static void cursorGlow(DrawContext context) {
        if (Float.isNaN(glowX)) {
            return;
        }

        int gx = Math.round(glowX);
        int gy = Math.round(glowY);

        for (int i = 3; i >= 1; i--) {
            int radius = 14 + i * 22;
            GuiTheme.roundedRect(context, gx - radius, gy - radius, radius * 2, radius * 2, radius,
                    GuiTheme.withAlpha(GuiTheme.ACCENTS[1], 0.016F));
        }
    }

    /** A barely-there accent tint that swells and fades over about twelve seconds. */
    private static void wash(DrawContext context, int width, int height) {
        float breath = 0.5F + 0.5F * (float) Math.sin(time * 0.52D);
        context.fill(0, 0, width, height,
                GuiTheme.withAlpha(GuiTheme.ACCENTS[0], 0.018F + 0.024F * breath));
    }

    /** A soft band of light that slides down the screen and wraps around, nudged by the cursor. */
    private static void aurora(DrawContext context, int width, int height) {
        float travel = (time * 0.055F) % 1.35F;
        int bandHeight = Math.max(48, height / 3);
        int top = Math.round((travel - 0.1F) * height + lookY * 12.0F);
        int middle = top + bandHeight / 2;

        int tint = GuiTheme.withAlpha(GuiTheme.ACCENTS[2], 0.075F);

        context.fillGradient(0, top, width, middle, 0x00000000, tint);
        context.fillGradient(0, middle, width, top + bandHeight, tint, 0x00000000);
    }

    /** Dust drifting upwards, each mote with its own speed, size, colour and phase. */
    private static void motes(DrawContext context, int width, int height, float dt) {
        int parallaxX = Math.round(lookX * 26.0F);
        int parallaxY = Math.round(lookY * 16.0F);

        for (int i = 0; i < PARTICLES; i++) {
            PY[i] -= SPEED[i] * dt;

            if (PY[i] < -0.03F) {
                PY[i] = 1.03F;
                PX[i] = (PX[i] + 0.37F) % 1.0F;
            }

            float sway = (float) Math.sin(time * 0.55D + PHASE[i]) * 0.012F;
            int x = Math.round((PX[i] + sway) * width) + parallaxX;
            int y = Math.round(PY[i] * height) + parallaxY;

            float pulse = 0.30F + 0.45F * (0.5F + 0.5F * (float) Math.sin(time * 1.6D + PHASE[i]));
            int size = Math.max(1, Math.round(SIZE[i]));

            // Cheap two-quad glow: a wide faint halo under a small bright core.
            context.fill(x - size * 2, y - size * 2, x + size * 3, y + size * 3,
                    GuiTheme.withAlpha(TINT[i], pulse * 0.14F));
            context.fill(x, y, x + size, y + size, GuiTheme.withAlpha(0xFFFFFFFF, pulse));
        }
    }

    /** Top and bottom darkening that breathes out of phase with the wash. */
    private static void vignette(DrawContext context, int width, int height) {
        float breath = 0.5F + 0.5F * (float) Math.sin(time * 0.35D + 1.2D);
        int edge = Math.max(48, height / 5);

        int top = GuiTheme.withAlpha(0xFF000000, 0.30F + 0.18F * breath);
        int bottom = GuiTheme.withAlpha(0xFF000000, 0.40F + 0.20F * breath);

        context.fillGradient(0, 0, width, edge, top, 0x00000000);
        context.fillGradient(0, height - edge, width, height, 0x00000000, bottom);
    }

    // ------------------------------------------------------------------ state

    /** Fixed seed, so the field looks the same every launch instead of shuffling. */
    private static void seed() {
        if (seeded) {
            return;
        }

        seeded = true;
        Random random = new Random(SEED);

        for (int i = 0; i < PARTICLES; i++) {
            PX[i] = random.nextFloat();
            PY[i] = random.nextFloat();
            SPEED[i] = 0.012F + random.nextFloat() * 0.032F;
            SIZE[i] = 1.0F + random.nextFloat() * 1.8F;
            PHASE[i] = random.nextFloat() * 6.2832F;
            TINT[i] = GuiTheme.ACCENTS[random.nextInt(GuiTheme.ACCENTS.length)];
        }
    }
}
