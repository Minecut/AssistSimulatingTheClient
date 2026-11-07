package com.dmod.eagle.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.dmod.eagle.EagleConfig;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.scoreboard.number.NumberFormat;
import net.minecraft.text.Text;

/**
 * The sidebar scoreboard, redrawn in the island's visual language.
 *
 * <p>Every piece of content is preserved: the objective name, every visible line, and its score.
 * Getting the line text right is the whole game here - a server almost never puts the visible string in
 * the entry name. What it does is set the owner to a short invisible token and hang the real text off
 * the team's prefix and suffix. Vanilla therefore builds each row as
 * {@code Team.decorateName(team, entry.name())}, and skipping that step leaves every row blank with
 * only the scores visible.
 *
 * <p>The vanilla renderer is cancelled while this is up, so there is never a double sidebar. Vanilla's
 * own 15-row cap and its highest-score-first ordering are kept.
 *
 * <p><b>Position.</b> Open chat and drag the panel; the position is written to the config on release.
 * Chat is used as the handle because it is the one screen you can open instantly mid-fight without
 * giving up control of the world, and because it already owns the mouse.
 */
public final class ScoreboardPanel {

    private static final int PADDING = 6;
    private static final int ROW_HEIGHT = 10;
    private static final int TITLE_HEIGHT = 11;
    private static final int MARGIN = 4;
    private static final int RADIUS = 6;

    /** Vanilla shows at most 15 rows; keeping the cap keeps the panel a glanceable size. */
    private static final int MAX_ROWS = 15;

    private static final long REFRESH_MS = 100L;

    private static final Comparator<ScoreboardEntry> BY_SCORE =
            Comparator.comparingInt(ScoreboardEntry::value).reversed()
                    .thenComparing(ScoreboardEntry::owner);

    private static final List<ScoreboardEntry> ENTRIES = new ArrayList<>();
    private static ScoreboardObjective cachedObjective;
    private static long refreshedAt;

    // ---- drag state, plus the bounds of the last frame so a click can be hit-tested
    private static boolean dragging;
    private static int grabX;
    private static int grabY;
    private static int lastX;
    private static int lastY;
    private static int lastWidth;
    private static int lastHeight;

    private ScoreboardPanel() {
    }

    /** True when our panel owns the sidebar right now, so vanilla must stand down. */
    public static boolean shouldReplace(MinecraftClient mc) {
        return mc != null
                && mc.world != null
                && mc.player != null
                && mc.options != null
                && !mc.options.hudHidden
                && EagleConfig.get().showScoreboard;
    }

    /** True while chat is open, which is when the panel can be moved. */
    public static boolean isEditable(MinecraftClient mc) {
        return mc != null && mc.currentScreen instanceof ChatScreen;
    }

    // ------------------------------------------------------------------ drag

    /** Called on a click while chat is open. Returns true when the click grabbed the panel. */
    public static boolean beginDrag(MinecraftClient mc, int mouseX, int mouseY) {
        if (!isEditable(mc) || !shouldReplace(mc) || lastWidth <= 0) {
            return false;
        }

        if (mouseX < lastX || mouseX > lastX + lastWidth
                || mouseY < lastY || mouseY > lastY + lastHeight) {
            return false;
        }

        dragging = true;
        grabX = mouseX - lastX;
        grabY = mouseY - lastY;
        return true;
    }

    /**
     * Moves the panel to follow the cursor. Driven from the screen render hook, which is the only place
     * that reliably hands over a scaled mouse position every frame.
     */
    public static void drag(MinecraftClient mc, int mouseX, int mouseY) {
        if (!dragging || mc == null || mc.getWindow() == null || lastWidth <= 0 || lastHeight <= 0) {
            return;
        }

        int screenWidth = mc.getWindow().getScaledWidth();
        int screenHeight = mc.getWindow().getScaledHeight();

        EagleConfig cfg = EagleConfig.get();
        cfg.scoreboardX = clamp(mouseX - grabX, 0, Math.max(0, screenWidth - lastWidth));
        cfg.scoreboardY = clamp(mouseY - grabY, 0, Math.max(0, screenHeight - lastHeight));
    }

    /** Called on release: drops the grab and persists the position. */
    public static void endDrag() {
        if (!dragging) {
            return;
        }

        dragging = false;
        EagleConfig.get().save();
    }

    // ------------------------------------------------------------------ hooks

    /** In-game HUD entry. Stands down while chat is open, because the screen hook covers that. */
    public static void renderInGame(DrawContext context, MinecraftClient mc) {
        if (isEditable(mc)) {
            return;
        }

        render(context, mc, false);
    }

    /** On top of the chat screen, where it can be seen and dragged. */
    public static void renderOverChat(DrawContext context, MinecraftClient mc, int mouseX, int mouseY) {
        drag(mc, mouseX, mouseY);
        render(context, mc, true);
    }

    // ------------------------------------------------------------------ drawing

    private static void render(DrawContext context, MinecraftClient mc, boolean editable) {
        if (!shouldReplace(mc)) {
            return;
        }

        Scoreboard scoreboard = mc.world.getScoreboard();
        ScoreboardObjective objective = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);

        if (objective == null) {
            return;
        }

        NumberFormat numberFormat = objective.getNumberFormat();
        List<ScoreboardEntry> entries = entries(scoreboard, objective);

        String title = objective.getDisplayName().getString();
        int titleWidth = mc.textRenderer.getWidth(title);
        int widest = titleWidth;

        for (ScoreboardEntry entry : entries) {
            int rowWidth = mc.textRenderer.getWidth(nameText(scoreboard, entry))
                    + 6
                    + mc.textRenderer.getWidth(scoreText(entry, numberFormat));

            widest = Math.max(widest, rowWidth);
        }

        int width = widest + PADDING * 2;
        int height = PADDING * 2 + TITLE_HEIGHT + entries.size() * ROW_HEIGHT;

        EagleConfig cfg = EagleConfig.get();
        int screenWidth = mc.getWindow().getScaledWidth();
        int screenHeight = mc.getWindow().getScaledHeight();

        int x = cfg.scoreboardX >= 0 ? cfg.scoreboardX : screenWidth - MARGIN - width;
        int y = cfg.scoreboardY >= 0 ? cfg.scoreboardY : MARGIN;

        x = clamp(x, 0, Math.max(0, screenWidth - width));
        y = clamp(y, 0, Math.max(0, screenHeight - height));

        lastX = x;
        lastY = y;
        lastWidth = width;
        lastHeight = height;

        // A one pixel accent ring marks the panel as grabbable while chat is open.
        if (editable) {
            GuiTheme.roundedRect(context, x - 1, y - 1, width + 2, height + 2, RADIUS + 1,
                    GuiTheme.withAlpha(GuiTheme.ACCENTS[3], 0.8F));
        }

        GuiTheme.roundedRect(context, x + 1, y + 2, width, height, RADIUS, GuiTheme.SHADOW);
        GuiTheme.roundedRect(context, x, y, width, height, RADIUS, 0xF0121216);

        int left = x + PADDING;
        int right = x + width - PADDING;

        context.drawText(mc.textRenderer, title,
                x + (width - titleWidth) / 2, y + PADDING, GuiTheme.ACCENTS[2], false);

        int cursor = y + PADDING + TITLE_HEIGHT;
        int nameRoom = widest;

        for (int i = 0; i < entries.size(); i++) {
            ScoreboardEntry entry = entries.get(i);
            int rowY = cursor + i * ROW_HEIGHT;

            if ((i & 1) == 0) {
                context.fill(left - 3, rowY - 1, right + 3, rowY + ROW_HEIGHT - 1,
                        GuiTheme.withAlpha(0xFFFFFFFF, 0.030F));
            }

            Text score = scoreText(entry, numberFormat);
            int scoreWidth = mc.textRenderer.getWidth(score);
            int room = Math.max(0, Math.min(nameRoom, width - PADDING * 2 - scoreWidth - 6));

            context.drawText(mc.textRenderer, trim(mc, nameText(scoreboard, entry).getString(), room),
                    left, rowY, 0xFFE8E8F0, false);
            context.drawText(mc.textRenderer, score, right - scoreWidth, rowY, 0xFFFF6060, false);
        }
    }

    /**
     * The visible text for a row.
     *
     * <p>{@code Team.decorateName} is what makes server sidebars readable: the entry name is usually an
     * invisible token and the real text lives in the team prefix. It is null-safe, so boards without
     * teams fall straight through to the entry name.
     */
    private static Text nameText(Scoreboard scoreboard, ScoreboardEntry entry) {
        return Team.decorateName(scoreboard.getScoreHolderTeam(entry.owner()), entry.name());
    }

    /**
     * The score, with the objective's number format applied so separators and units survive.
     *
     * <p>Deliberately not {@code entry.formatted(format)}. That method is
     * {@code Objects.requireNonNullElse(numberFormatOverride, format).format(value)}, so when the entry
     * has no override <em>and</em> the objective has no number format - the ordinary case for most
     * boards - it throws {@code NullPointerException: defaultObj}. Resolving the precedence here and
     * formatting directly keeps the same result for boards that do use a format while making the
     * no-format case a plain integer.
     */
    private static Text scoreText(ScoreboardEntry entry, NumberFormat objectiveFormat) {
        NumberFormat format = entry.numberFormatOverride() != null
                ? entry.numberFormatOverride()
                : objectiveFormat;

        return format == null
                ? Text.literal(Integer.toString(entry.value()))
                : format.format(entry.value());
    }

    /** Cached entry list: visible rows only, highest score first, capped at vanilla's 15. */
    private static List<ScoreboardEntry> entries(Scoreboard scoreboard, ScoreboardObjective objective) {
        long now = System.currentTimeMillis();

        if (objective == cachedObjective && now - refreshedAt < REFRESH_MS) {
            return ENTRIES;
        }

        cachedObjective = objective;
        refreshedAt = now;
        ENTRIES.clear();

        for (ScoreboardEntry entry : scoreboard.getScoreboardEntries(objective)) {
            if (!entry.hidden()) {
                ENTRIES.add(entry);
            }
        }

        ENTRIES.sort(BY_SCORE);

        while (ENTRIES.size() > MAX_ROWS) {
            ENTRIES.remove(ENTRIES.size() - 1);
        }

        return ENTRIES;
    }

    // ------------------------------------------------------------------ helpers

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String trim(MinecraftClient mc, String text, int maxWidth) {
        if (maxWidth <= 0) {
            return "";
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
