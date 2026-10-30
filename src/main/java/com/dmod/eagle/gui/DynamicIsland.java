package com.dmod.eagle.gui;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import com.dmod.eagle.EagleConfig;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * The pill at the top of the screen.
 *
 * <p>Sits idle showing the clock, and swells out to announce a module toggle before settling back.
 * A stadium shape - a rounded rect whose corner radius is half its height - is the same trick the
 * phone version uses, and {@link GuiTheme#roundedRect} already draws it row by row, so no texture or
 * shader is involved.
 *
 * <p>Everything animates off two smoothed floats: {@code presence} (how much of the announcement is
 * showing) drives the text crossfade and the width target at once, so the pill and the words can never
 * disagree about which state they are in. The clock string is rebuilt once per second rather than per
 * frame.
 *
 * <p>It has two homes for the same reason the toast does: the in-game HUD, and on top of a screen. The
 * clock belongs to the HUD, but an announcement should follow you onto the settings screen, since that
 * is one of the places a module gets switched on.
 */
public final class DynamicIsland {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final int HEIGHT = 18;
    private static final int TOP = 4;
    private static final int PADDING = 10;
    private static final int MIN_WIDTH = 66;

    private static final long FLASH_MS = 1700L;

    private static Text flashText = Text.empty();
    private static long flashUntil;
    private static float presence;
    private static float width = -1.0F;
    private static long lastFrameMs;

    private static int lastSecond = -1;
    private static String clockText = "";

    private DynamicIsland() {
    }

    /** Announces a module change. Called from the keybind loops and from the click GUI. */
    public static void flash(String module, boolean on) {
        MutableText text = Text.literal(on ? "[+] " : "[-] ")
                .formatted(on ? Formatting.GREEN : Formatting.RED)
                .append(Text.literal(module).formatted(Formatting.WHITE));

        flashText = text;
        flashUntil = System.currentTimeMillis() + FLASH_MS;
    }

    /** True while an announcement is still on screen. */
    public static boolean isFlashing() {
        return System.currentTimeMillis() < flashUntil;
    }

    // ------------------------------------------------------------------ hooks

    /** In-game: quiet while a screen is up, because the screen hook covers that case. */
    public static void renderHud(DrawContext context) {
        if (MinecraftClient.getInstance().currentScreen != null) {
            return;
        }

        draw(context);
    }

    /** On a screen: only while an announcement is running, so the clock stays an in-game thing. */
    public static void renderScreen(DrawContext context) {
        if (presence <= 0.02F) {
            return;
        }

        draw(context);
    }

    // ------------------------------------------------------------------ drawing

    private static void draw(DrawContext context) {
        EagleConfig cfg = EagleConfig.get();

        if (!cfg.showIsland) {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc.player == null || mc.textRenderer == null || mc.options == null || mc.options.hudHidden) {
            return;
        }

        long now = System.currentTimeMillis();
        float dt = lastFrameMs == 0L ? 0.016F : Math.min(0.1F, (now - lastFrameMs) / 1000.0F);
        lastFrameMs = now;

        presence = GuiTheme.approach(presence, now < flashUntil ? 1.0F : 0.0F, 0.45F, dt);

        String clock = clock(mc);
        int clockWidth = mc.textRenderer.getWidth(clock);
        int flashWidth = mc.textRenderer.getWidth(flashText);

        float idleWidth = Math.max(MIN_WIDTH, clockWidth + PADDING * 2.0F);
        float peakWidth = Math.max(idleWidth, flashWidth + PADDING * 2.0F);
        float target = idleWidth + (peakWidth - idleWidth) * presence;

        width = width < 0.0F ? target : GuiTheme.approach(width, target, 0.4F, dt);

        int w = Math.max(24, Math.round(width));
        int x = (mc.getWindow().getScaledWidth() - w) / 2;
        int radius = HEIGHT / 2;
        int textY = TOP + (HEIGHT - 8) / 2;

        GuiTheme.roundedRect(context, x + 1, TOP + 2, w, HEIGHT, radius, GuiTheme.SHADOW);
        GuiTheme.roundedRect(context, x, TOP, w, HEIGHT, radius, 0xF0121216);

        // The clock fades out exactly as the announcement fades in, both centred in the pill.
        if (presence < 0.99F) {
            context.drawText(mc.textRenderer, clock,
                    x + (w - clockWidth) / 2, textY,
                    GuiTheme.withAlpha(0xFFD8D8E2, 1.0F - presence), false);
        }

        if (presence > 0.01F) {
            context.drawText(mc.textRenderer, flashText,
                    x + (w - flashWidth) / 2, textY,
                    GuiTheme.withAlpha(0xFFFFFFFF, presence), false);
        }
    }

    /** Rebuilt once per second; the formatter is far too expensive to run per frame. */
    private static String clock(MinecraftClient mc) {
        LocalTime now = LocalTime.now();

        if (now.getSecond() != lastSecond) {
            lastSecond = now.getSecond();
            clockText = now.format(CLOCK);
        }

        return clockText;
    }
}
