package com.dmod.eagle;

import java.util.ArrayList;
import java.util.List;

import com.dmod.eagle.gui.GuiTheme;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Top left readout so the state is visible without opening a menu.
 *
 * <p>One module per line, stacked on a single rounded panel with the client name across the top. The
 * panel is sized from the widest line rather than being a fixed box, so it hugs the content whatever
 * names or activity tags are showing.
 */
public final class EagleHud {

    private static final String TITLE = "EagleClient";

    private static final int MARGIN = 4;
    private static final int LINE_HEIGHT = 10;
    private static final int PADDING = 5;
    private static final int RADIUS = 5;

    /** Vertical room reserved for the clock pill at the top of the screen. */
    private static final int ISLAND_CLEARANCE = 22;

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

        List<Text> segments = new ArrayList<>(10);
        segments.add(segment("Eagle", eagleOn, eagleOn && EagleLogic.isEngaged() ? "sneak" : null));
        segments.add(segment("CPS", cpsOn, cpsOn && CpsBoostLogic.isPacing() ? "hold" : null));
        segments.add(segment("Sprint", cfg.sprintEnabled, null));
        segments.add(segment("Armour", cfg.autoArmourEnabled,
                cfg.autoArmourEnabled && AutoArmourLogic.isEquipping() ? "swap" : null));
        segments.add(segment("Aim", cfg.aimEnabled,
                cfg.aimEnabled && AimAssistLogic.isAiming() ? "lock" : null));
        segments.add(segment("Inv", cfg.invChestEnabled,
                cfg.invChestEnabled && InvChestLogic.isTaking() ? "take" : null));
        segments.add(segment("ESP", cfg.espEnabled,
                cfg.espEnabled && EspLogic.isActive() ? Integer.toString(EspLogic.trackedCount()) : null));
        segments.add(segment("BI", cfg.blockInEnabled,
                cfg.blockInEnabled && BlockInLogic.isWorking() ? "work" : null));
        segments.add(segment("Shop", cfg.remoteShopEnabled,
                cfg.remoteShopEnabled && RemoteShopLogic.hasStash() ? "ready" : null));
        segments.add(segment("Name", cfg.customNameEnabled,
                cfg.customNameEnabled && !cfg.customName.isEmpty() ? cfg.customName : null));

        int widest = mc.textRenderer.getWidth(TITLE);

        for (Text segment : segments) {
            widest = Math.max(widest, mc.textRenderer.getWidth(segment));
        }

        int panelWidth = widest + PADDING * 2;
        int panelHeight = PADDING * 2 + LINE_HEIGHT * (segments.size() + 1);

        int x = MARGIN;
        int y = MARGIN + ISLAND_CLEARANCE;

        GuiTheme.roundedRect(context, x + 1, y + 2, panelWidth, panelHeight, RADIUS, GuiTheme.SHADOW);
        GuiTheme.roundedRect(context, x, y, panelWidth, panelHeight, RADIUS, GuiTheme.PANEL);

        int textX = x + PADDING;
        int textY = y + PADDING;

        context.drawText(mc.textRenderer, TITLE, textX, textY, GuiTheme.ACCENTS[0], true);
        textY += LINE_HEIGHT;

        for (Text segment : segments) {
            context.drawText(mc.textRenderer, segment, textX, textY, 0xFFFFFF, true);
            textY += LINE_HEIGHT;
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

        return text;
    }
}
