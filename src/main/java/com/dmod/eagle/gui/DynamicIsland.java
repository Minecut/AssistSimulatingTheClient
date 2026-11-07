package com.dmod.eagle.gui;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import com.dmod.eagle.EagleConfig;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * The pill at the top of the screen, which is also the tab panel.
 *
 * <p>Three states share one piece of geometry. Idle it is a stadium showing the clock; a module toggle
 * swells it sideways to announce the change; holding tab grows it into a rounded square carrying the
 * player list. A stadium is just a rounded rect whose radius is half its height, so raising the height
 * and easing the radius back to a fixed value turns one into the other without a second shape.
 *
 * <p>All of it animates off smoothed floats - {@code presence} for announcements, {@code tab} for the
 * expansion - and the width, height and radius targets are recomputed from those every frame, so the
 * box, its corners and its contents can never disagree about which state they are in.
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

    /** Corner radius once fully expanded, which reads as a rounded square rather than a pill. */
    private static final int TAB_RADIUS = 16;

    private static final long FLASH_MS = 1700L;

    /** The status line carries a frame rate, so it is rebuilt a few times a second, not per frame. */
    private static final long STATUS_REFRESH_MS = 250L;

    /** Servers with long hostnames would otherwise push the pill across half the screen. */
    private static final int MAX_SERVER_CHARS = 22;

    /** List content only appears once the box is this far open, so text never spills outside it. */
    private static final float CONTENT_THRESHOLD = 0.75F;

    private static Text flashText = Text.empty();
    private static long flashUntil;

    private static float presence;
    private static float tab;
    private static float width = -1.0F;
    private static float height = HEIGHT;
    private static long lastFrameMs;

    private static long statusAt;
    private static String statusText = "";

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
        tab = GuiTheme.approach(tab, TabPanel.isOpen(mc) ? 1.0F : 0.0F, 0.55F, dt);

        String clock = statusLine(mc);
        int clockWidth = mc.textRenderer.getWidth(clock);
        int flashWidth = mc.textRenderer.getWidth(flashText);

        float idleWidth = Math.max(MIN_WIDTH, clockWidth + PADDING * 2.0F);
        float peakWidth = Math.max(idleWidth, flashWidth + PADDING * 2.0F);
        float pillWidth = idleWidth + (peakWidth - idleWidth) * presence;

        float targetWidth = pillWidth + (TabPanel.WIDTH - pillWidth) * tab;
        float targetHeight = HEIGHT + (TabPanel.preferredHeight(mc) - HEIGHT) * tab;

        width = width < 0.0F ? targetWidth : GuiTheme.approach(width, targetWidth, 0.4F, dt);
        height = GuiTheme.approach(height, targetHeight, 0.4F, dt);

        int w = Math.max(24, Math.round(width));
        int h = Math.max(HEIGHT, Math.round(height));
        int x = (mc.getWindow().getScaledWidth() - w) / 2;
        int radius = Math.round(HEIGHT / 2.0F + (TAB_RADIUS - HEIGHT / 2.0F) * tab);

        GuiTheme.roundedRect(context, x + 1, TOP + 2, w, h, radius, GuiTheme.SHADOW);
        GuiTheme.roundedRect(context, x, TOP, w, h, radius, 0xF0121216);

        // The pill text steps aside quickly so it cannot overlap the list while the box opens.
        float pillAlpha = Math.max(0.0F, 1.0F - tab * 1.6F);

        if (pillAlpha > 0.01F) {
            int textY = TOP + (HEIGHT - 8) / 2;

            if (presence < 0.99F) {
                context.drawText(mc.textRenderer, clock,
                        x + (w - clockWidth) / 2, textY,
                        GuiTheme.withAlpha(0xFFD8D8E2, pillAlpha * (1.0F - presence)), false);
            }

            if (presence > 0.01F) {
                context.drawText(mc.textRenderer, flashText,
                        x + (w - flashWidth) / 2, textY,
                        GuiTheme.withAlpha(0xFFFFFFFF, pillAlpha * presence), false);
            }
        }

        if (tab > CONTENT_THRESHOLD) {
            TabPanel.render(context, mc, x, TOP, w, h, (tab - CONTENT_THRESHOLD) / (1.0F - CONTENT_THRESHOLD));
        }
    }

    /**
     * The idle line: client name, clock, frame rate, and where we are connected.
     *
     * <p>Cached for a quarter second. The clock only needs a second's resolution, but the frame rate
     * would look frozen if it shared that rate, and formatting this string sixty times a second would
     * be wasteful for something that barely changes.
     */
    private static String statusLine(MinecraftClient mc) {
        long now = System.currentTimeMillis();

        if (!statusText.isEmpty() && now - statusAt < STATUS_REFRESH_MS) {
            return statusText;
        }

        statusAt = now;
        statusText = "Eagle | "
                + LocalTime.now().format(CLOCK)
                + " | " + mc.getCurrentFps()
                + " | " + serverLabel(mc);

        return statusText;
    }

    private static String serverLabel(MinecraftClient mc) {
        ServerInfo info = mc.getCurrentServerEntry();

        if (info != null && info.address != null && !info.address.isBlank()) {
            String address = info.address;
            return address.length() <= MAX_SERVER_CHARS
                    ? address
                    : address.substring(0, MAX_SERVER_CHARS - 2) + "..";
        }

        return mc.isInSingleplayer() ? "singleplayer" : "no server";
    }
}
