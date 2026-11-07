package com.dmod.eagle.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.dmod.eagle.mixin.PlayerListHudAccessor;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;

/**
 * The contents of the tab list, drawn inside the expanded island.
 *
 * <p>Only the <em>data</em> comes from vanilla. The layout, the row striping, the latency colouring
 * and the rounded container are all new - the vanilla screen is suppressed while this is up, so there
 * is exactly one list on screen at a time.
 *
 * <p>The entry list is rebuilt at most every 200 ms rather than every frame: it involves an
 * allocation and a sort, and a player joining does not need to appear within 16 ms. Latency is shown
 * as text instead of vanilla's five ping-bar textures, because the bar icons are tied to vanilla's own
 * layout metrics and would not fit this box.
 */
public final class TabPanel {

    /** Width of the expanded island, in scaled pixels. */
    public static final int WIDTH = 216;

    private static final int PADDING = 9;
    private static final int ROW_HEIGHT = 11;
    private static final int MAX_ROWS = 12;
    private static final int TITLE_HEIGHT = 12;

    private static final long REFRESH_MS = 200L;

    private static final List<PlayerListEntry> ENTRIES = new ArrayList<>();
    private static long refreshedAt;

    private TabPanel() {
    }

    /** True while the tab key is held somewhere the panel can actually be drawn. */
    public static boolean isOpen(MinecraftClient mc) {
        return mc != null
                && mc.player != null
                && mc.world != null
                && mc.options != null
                && mc.getNetworkHandler() != null
                && !mc.options.hudHidden
                && mc.options.playerListKey.isPressed();
    }

    /** Height the island should grow to, given how many players there are right now. */
    public static int preferredHeight(MinecraftClient mc) {
        if (mc == null || mc.getNetworkHandler() == null) {
            return 104;
        }

        int rows = Math.min(entries().size(), MAX_ROWS);
        int height = PADDING + TITLE_HEIGHT + rows * ROW_HEIGHT + PADDING;

        if (footer(mc) != null) {
            height += ROW_HEIGHT;
        }

        return Math.max(92, Math.min(height, 210));
    }

    public static void render(DrawContext context, MinecraftClient mc, int x, int y, int width, int height,
            float alpha) {
        TextRenderer textRenderer = mc.textRenderer;
        PlayerListHud hud = mc.inGameHud.getPlayerListHud();
        List<PlayerListEntry> entries = entries();

        int left = x + PADDING;
        int right = x + width - PADDING;
        int cursor = y + PADDING;

        String title = firstLine(header(mc));

        if (title == null) {
            title = "ONLINE " + entries.size();
        }

        context.drawText(textRenderer, trim(textRenderer, title, width - PADDING * 2),
                left, cursor, GuiTheme.withAlpha(0xFF8FD8FF, alpha), false);

        cursor += TITLE_HEIGHT;
        context.drawHorizontalLine(left, right, cursor - 2, GuiTheme.withAlpha(0x60FFFFFF, alpha));

        int rows = Math.min(entries.size(), MAX_ROWS);
        float rowAlpha = alpha;

        for (int i = 0; i < rows; i++) {
            PlayerListEntry entry = entries.get(i);
            int rowY = cursor + i * ROW_HEIGHT;

            if ((i & 1) == 0) {
                context.fill(left - 3, rowY - 1, right + 3, rowY + ROW_HEIGHT - 1,
                        GuiTheme.withAlpha(0xFFFFFFFF, 0.030F * rowAlpha));
            }

            boolean self = mc.player != null
                    && entry.getProfile().getId().equals(mc.player.getUuid());

            String name = entryName(hud, entry);
            String trimmed = trim(textRenderer, name, width - PADDING * 2 - 36);

            context.drawText(textRenderer, trimmed, left, rowY,
                    GuiTheme.withAlpha(self ? 0xFF7FE08A : 0xFFE8E8F0, rowAlpha), false);

            int latency = entry.getLatency();
            String ping = latency < 0 ? "--" : latency + "ms";
            int pingColor = latency < 0
                    ? 0xFF8A8A96
                    : latency < 80 ? 0xFF54E08A : latency < 200 ? 0xFFF0C04C : 0xFFF06060;

            context.drawText(textRenderer, ping, right - textRenderer.getWidth(ping), rowY,
                    GuiTheme.withAlpha(pingColor, rowAlpha), false);
        }

        if (rows == 0) {
            context.drawText(textRenderer, "no players", left, cursor,
                    GuiTheme.withAlpha(0xFF8A8A96, alpha), false);
        }

        String footer = firstLine(footer(mc));

        if (footer != null) {
            context.drawText(textRenderer, trim(textRenderer, footer, width - PADDING * 2),
                    left, y + height - PADDING - 8, GuiTheme.withAlpha(0xFF9A9AA6, alpha), false);
        }
    }

    // ------------------------------------------------------------------ data

    /** Cached entry list, sorted by name. Rebuilt on a timer rather than per frame. */
    private static List<PlayerListEntry> entries() {
        long now = System.currentTimeMillis();

        if (now - refreshedAt < REFRESH_MS && !ENTRIES.isEmpty()) {
            return ENTRIES;
        }

        refreshedAt = now;
        ENTRIES.clear();

        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc == null || mc.getNetworkHandler() == null) {
            return ENTRIES;
        }

        ENTRIES.addAll(mc.getNetworkHandler().getPlayerList());
        ENTRIES.sort(Comparator.comparing(TabPanel::entryName, String.CASE_INSENSITIVE_ORDER));

        return ENTRIES;
    }

    private static String entryName(PlayerListEntry entry) {
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc == null || mc.inGameHud == null) {
            return entry.getProfile().getName();
        }

        // Vanilla's own accessor applies team colours and the spectator/npc formatting.
        return entryName(mc.inGameHud.getPlayerListHud(), entry);
    }

    private static String entryName(PlayerListHud hud, PlayerListEntry entry) {
        if (hud == null) {
            return entry.getProfile().getName();
        }

        Text name = hud.getPlayerName(entry);
        return name == null ? entry.getProfile().getName() : name.getString();
    }

    private static Text header(MinecraftClient mc) {
        return accessor(mc) == null ? null : accessor(mc).eagle$header();
    }

    private static Text footer(MinecraftClient mc) {
        return accessor(mc) == null ? null : accessor(mc).eagle$footer();
    }

    private static PlayerListHudAccessor accessor(MinecraftClient mc) {
        if (mc == null || mc.inGameHud == null) {
            return null;
        }

        PlayerListHud hud = mc.inGameHud.getPlayerListHud();
        return hud == null ? null : (PlayerListHudAccessor) hud;
    }

    // ------------------------------------------------------------------ text helpers

    /** Server headers and footers arrive multi-line; the panel has room for one line of each. */
    private static String firstLine(Text text) {
        if (text == null) {
            return null;
        }

        for (String line : text.getString().split("\n")) {
            if (!line.isBlank()) {
                return line;
            }
        }

        return null;
    }

    private static String trim(TextRenderer textRenderer, String text, int maxWidth) {
        if (textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }

        String cut = text;

        while (cut.length() > 1 && textRenderer.getWidth(cut + "..") > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }

        return cut + "..";
    }
}
