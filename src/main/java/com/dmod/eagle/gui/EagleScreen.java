package com.dmod.eagle.gui;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import com.dmod.eagle.EagleConfig;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * The click GUI.
 *
 * <p>Four category columns, LiquidBounce style: a module row toggles on the left indicator and expands
 * its settings when clicked anywhere else, booleans are checkboxes, numbers are drag tracks, and each
 * column scrolls on its own when the content outgrows the screen.
 *
 * <p>The backdrop is vanilla's own {@code renderBackground}, which brings the blur and darkening along
 * with it. {@code Screen.render} calls that itself, so this override draws the background once and then
 * the panels, and deliberately does not chain to {@code super}.
 */
public class EagleScreen extends Screen {

    private static final int INDICATOR_SIZE = 8;
    private static final int INDICATOR_INSET = 4;

    /** Kept across openings so scroll position and expanded rows survive. */
    private static List<GuiCategory> shared;

    private long lastFrameMs;
    private float openProgress;
    private boolean dirty;

    private GuiSetting.Number dragging;
    private int dragLeft;
    private int dragWidth;

    public EagleScreen() {
        super(Text.literal("Eagle"));

        if (shared == null) {
            shared = GuiCategory.buildAll();
        }

        // Reset the transient look so reopening the GUI does not replay an animation from last time.
        for (GuiCategory category : shared) {
            category.hoverHeader = 0.0F;

            for (GuiModule module : category.modules) {
                module.hover = 0.0F;
                module.expand = module.expanded ? 1.0F : 0.0F;
            }
        }

        this.lastFrameMs = System.currentTimeMillis();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ------------------------------------------------------------------ layout

    private record Panel(GuiCategory category, int x, int y, int w, int h) {

        int contentTop() {
            return this.y + GuiTheme.HEADER_HEIGHT;
        }

        int contentHeight() {
            return this.h - GuiTheme.HEADER_HEIGHT;
        }

        int rowWidth() {
            return this.w - GuiTheme.PADDING * 2;
        }

        int rowLeft() {
            return this.x + GuiTheme.PADDING;
        }
    }

    private List<Panel> layout() {
        int count = shared.size();
        int totalWidth = count * GuiTheme.PANEL_WIDTH + Math.max(0, count - 1) * GuiTheme.PANEL_GAP;
        int startX = (this.width - totalWidth) / 2;
        int top = 22;
        int available = Math.max(GuiTheme.HEADER_HEIGHT + 20, this.height - top - 14);

        int tallest = 0;

        for (GuiCategory category : shared) {
            tallest = Math.max(tallest, category.animatedContentHeight());
        }

        int panelHeight = Math.min(available, GuiTheme.HEADER_HEIGHT + tallest);

        List<Panel> panels = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            panels.add(new Panel(
                    shared.get(i),
                    startX + i * (GuiTheme.PANEL_WIDTH + GuiTheme.PANEL_GAP),
                    top,
                    GuiTheme.PANEL_WIDTH,
                    panelHeight));
        }

        return panels;
    }

    // ------------------------------------------------------------------ render

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        long now = System.currentTimeMillis();
        float dt = Math.min(0.1F, (now - this.lastFrameMs) / 1000.0F);
        this.lastFrameMs = now;

        this.openProgress = GuiTheme.approach(this.openProgress, 1.0F, 0.5F, dt);

        this.renderBackground(context, mouseX, mouseY, delta);

        List<Panel> panels = layout();

        for (int i = 0; i < panels.size(); i++) {
            Panel panel = panels.get(i);
            GuiCategory category = panel.category();

            // Staggered reveal: each column lags the one before it slightly.
            category.slide = Math.max(0.0F, Math.min(1.0F, this.openProgress * 1.6F - i * 0.12F));

            for (GuiModule module : category.modules) {
                module.expand = GuiTheme.approach(module.expand, module.expanded ? 1.0F : 0.0F, 0.5F, dt);
            }

            clampScroll(panel);
            drawPanel(context, panel, mouseX, mouseY, dt);
        }

        drawChrome(context);
    }

    private void drawChrome(DrawContext context) {
        float alpha = GuiTheme.easeOut(this.openProgress);

        context.drawText(this.textRenderer, "Eagle", 8, 7,
                GuiTheme.withAlpha(GuiTheme.ACCENTS[0], alpha), true);

        String hint = "Right Shift / Esc to close    \u00b7    .inv speed <s>";
        context.drawText(this.textRenderer, hint,
                this.width - 8 - this.textRenderer.getWidth(hint), this.height - 12,
                GuiTheme.withAlpha(GuiTheme.TEXT_FOOTER, alpha), true);
    }

    private void drawPanel(DrawContext context, Panel panel, int mouseX, int mouseY, float dt) {
        GuiCategory category = panel.category();
        float alpha = GuiTheme.easeOut(category.slide);

        if (alpha <= 0.01F) {
            return;
        }

        int x = panel.x() + Math.round((1.0F - alpha) * 34.0F);
        int y = panel.y();
        int w = panel.w();
        int h = panel.h();

        boolean headerHovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + GuiTheme.HEADER_HEIGHT;
        category.hoverHeader = GuiTheme.approach(category.hoverHeader, headerHovered ? 1.0F : 0.0F, 0.5F, dt);

        // drop shadow, then the body
        GuiTheme.roundedRect(context, x + 2, y + 3, w, h, GuiTheme.CORNER,
                GuiTheme.withAlpha(GuiTheme.SHADOW, alpha));
        GuiTheme.roundedRect(context, x, y, w, h, GuiTheme.CORNER, GuiTheme.withAlpha(GuiTheme.PANEL, alpha));

        // header band, with its bottom corners squared off
        int headerColor = GuiTheme.lerpColor(GuiTheme.HEADER, 0xFF26262E, category.hoverHeader);
        GuiTheme.roundedRect(context, x, y, w, GuiTheme.HEADER_HEIGHT, GuiTheme.CORNER,
                GuiTheme.withAlpha(headerColor, alpha));
        context.fill(x, y + GuiTheme.HEADER_HEIGHT - GuiTheme.CORNER, x + w, y + GuiTheme.HEADER_HEIGHT,
                GuiTheme.withAlpha(headerColor, alpha));

        // accent underline
        context.fill(x + GuiTheme.CORNER, y + GuiTheme.HEADER_HEIGHT - 2, x + w - GuiTheme.CORNER,
                y + GuiTheme.HEADER_HEIGHT, GuiTheme.withAlpha(category.accent, alpha));

        context.drawText(this.textRenderer, category.name, x + 6, y + 5,
                GuiTheme.withAlpha(category.accent, alpha), true);

        // content, clipped to the panel body
        context.enableScissor(x, y + GuiTheme.HEADER_HEIGHT, x + w, y + h);

        int rowLeft = panel.rowLeft();
        int rowWidth = panel.rowWidth();
        int cursorY = y + GuiTheme.HEADER_HEIGHT + GuiTheme.PADDING - Math.round(category.scroll);

        for (GuiModule module : category.modules) {
            drawModule(context, category, module, rowLeft, cursorY, rowWidth, mouseX, mouseY, alpha, dt);
            cursorY += module.currentHeight();
        }

        // hairline border on top of the fill so the panel reads as a card
        context.drawBorder(x, y, w, h, GuiTheme.withAlpha(GuiTheme.BORDER, alpha));
        context.disableScissor();

        drawScrollbar(context, panel, alpha);
    }

    private void drawScrollbar(DrawContext context, Panel panel, float alpha) {
        GuiCategory category = panel.category();
        int content = category.animatedContentHeight();
        int view = panel.contentHeight();

        if (content <= view || view <= 0) {
            return;
        }

        int trackTop = panel.contentTop() + 2;
        int trackHeight = view - 4;
        int thumbHeight = Math.max(10, Math.round(trackHeight * (view / (float) content)));
        float max = content - view;
        int thumbTop = trackTop + Math.round((trackHeight - thumbHeight) * (category.scroll / max));

        GuiTheme.roundedRect(context, panel.x() + panel.w() - 3, thumbTop, 2, thumbHeight, 1,
                GuiTheme.withAlpha(category.accent, alpha * 0.75F));
    }

    private void drawModule(DrawContext context, GuiCategory category, GuiModule module,
                            int rowLeft, int y, int rowWidth, int mouseX, int mouseY,
                            float alpha, float dt) {
        boolean hovered = mouseX >= rowLeft && mouseX < rowLeft + rowWidth
                && mouseY >= y && mouseY < y + GuiTheme.MODULE_HEIGHT;

        module.hover = GuiTheme.approach(module.hover, hovered ? 1.0F : 0.0F, 0.6F, dt);

        if (module.hover > 0.01F) {
            GuiTheme.roundedRect(context, rowLeft, y, rowWidth, GuiTheme.MODULE_HEIGHT, 3,
                    GuiTheme.withAlpha(GuiTheme.HOVER, module.hover * alpha));
        }

        boolean on = module.isEnabled();
        int boxX = rowLeft + INDICATOR_INSET;
        int boxY = y + (GuiTheme.MODULE_HEIGHT - INDICATOR_SIZE) / 2;

        GuiTheme.roundedRect(context, boxX, boxY, INDICATOR_SIZE, INDICATOR_SIZE, 2,
                GuiTheme.withAlpha(on ? category.accent : 0xFF3A3A44, alpha));

        if (on) {
            // inner dot, so the indicator reads as "on" even at a glance
            context.fill(boxX + 3, boxY + 3, boxX + INDICATOR_SIZE - 3, boxY + INDICATOR_SIZE - 3,
                    GuiTheme.withAlpha(0xFF14141A, alpha));
        }

        int textY = y + (GuiTheme.MODULE_HEIGHT - 8) / 2;
        int nameColor = on ? GuiTheme.TEXT_ON : GuiTheme.TEXT_OFF;
        context.drawText(this.textRenderer, module.name, boxX + INDICATOR_SIZE + 5, textY,
                GuiTheme.withAlpha(nameColor, alpha), true);

        int right = rowLeft + rowWidth - 4;

        chevron(context, right - 6, y + 6, module.expanded,
                GuiTheme.withAlpha(GuiTheme.TEXT_KEY, alpha));

        String key = module.keyLabel();

        if (!key.isEmpty()) {
            right -= 12;
            int keyWidth = this.textRenderer.getWidth(key);
            context.drawText(this.textRenderer, key, right - keyWidth, textY,
                    GuiTheme.withAlpha(GuiTheme.TEXT_KEY, alpha), true);
        }

        if (module.settingsHeight() <= 0) {
            return;
        }

        int settingTop = y + GuiTheme.MODULE_HEIGHT;
        int settingLeft = rowLeft + 8;
        int settingWidth = rowWidth - 8;
        int cursorY = settingTop;

        for (GuiSetting setting : module.settings) {
            drawSetting(context, category, setting, settingLeft, cursorY, settingWidth,
                    mouseX, mouseY, alpha);

            cursorY += GuiTheme.SETTING_HEIGHT;
        }

        // Fade the settings block in from the top while it expands.
        float reveal = module.expand;

        if (reveal < 1.0F) {
            context.fill(settingLeft, settingTop, settingLeft + settingWidth, cursorY,
                    GuiTheme.withAlpha(GuiTheme.PANEL, (1.0F - reveal) * alpha));
        }
    }

    private void drawSetting(DrawContext context, GuiCategory category, GuiSetting setting,
                             int rowLeft, int y, int rowWidth, int mouseX, int mouseY, float alpha) {
        int textY = y + (GuiTheme.SETTING_HEIGHT - 8) / 2;

        if (setting.checkbox()) {
            int boxX = rowLeft + 2;
            int boxSize = 6;
            int boxY = y + (GuiTheme.SETTING_HEIGHT - boxSize) / 2;
            boolean on = setting.ratio() > 0.5F;

            GuiTheme.roundedRect(context, boxX, boxY, boxSize, boxSize, 1,
                    GuiTheme.withAlpha(on ? category.accent : 0xFF3A3A44, alpha));
            context.drawText(this.textRenderer, setting.name, boxX + boxSize + 5, textY,
                    GuiTheme.withAlpha(GuiTheme.TEXT_SETTING, alpha), true);
            return;
        }

        GuiTheme.roundedRect(context, rowLeft, y + 1, rowWidth, GuiTheme.SETTING_HEIGHT - 2, 2,
                GuiTheme.withAlpha(GuiTheme.TRACK, alpha));

        int filled = Math.round(rowWidth * setting.ratio());

        if (filled > 1) {
            GuiTheme.roundedRect(context, rowLeft, y + 1, filled, GuiTheme.SETTING_HEIGHT - 2, 2,
                    GuiTheme.withAlpha(category.accent, alpha * 0.35F));
        }

        String value = setting.valueText();
        int valueWidth = value == null ? 0 : this.textRenderer.getWidth(value);
        int nameSpace = rowWidth - valueWidth - 12;

        context.drawText(this.textRenderer, trim(setting.name, nameSpace), rowLeft + 4, textY,
                GuiTheme.withAlpha(GuiTheme.TEXT_SETTING, alpha), true);

        if (value != null) {
            context.drawText(this.textRenderer, value, rowLeft + rowWidth - 4 - valueWidth, textY,
                    GuiTheme.withAlpha(GuiTheme.TEXT_VALUE, alpha), true);
        }
    }

    private void chevron(DrawContext context, int x, int y, boolean expanded, int color) {
        for (int i = 0; i < 4; i++) {
            int width = 7 - i * 2;

            if (width <= 0) {
                return;
            }

            int row = expanded ? 3 - i : i;
            context.fill(x + i, y + row, x + i + width, y + row + 1, color);
        }
    }

    private String trim(String text, int maxWidth) {
        if (maxWidth <= 0) {
            return "";
        }

        if (this.textRenderer.getWidth(text) <= maxWidth) {
            return text;
        }

        String cut = text;

        while (cut.length() > 1 && this.textRenderer.getWidth(cut + "..") > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }

        return cut + "..";
    }

    private void clampScroll(Panel panel) {
        GuiCategory category = panel.category();
        float max = Math.max(0.0F, category.animatedContentHeight() - panel.contentHeight());
        category.scroll = Math.max(0.0F, Math.min(max, category.scroll));
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            return super.mouseClicked(mouseX, mouseY, button);
        }

        for (Panel panel : layout()) {
            Panel hit = panel;

            if (mouseX < hit.x() || mouseX >= hit.x() + hit.w()
                    || mouseY < hit.contentTop() || mouseY >= hit.y() + hit.h()) {
                continue;
            }

            GuiCategory category = hit.category();
            int rowLeft = hit.rowLeft();
            int cursorY = hit.contentTop() + GuiTheme.PADDING - Math.round(category.scroll);

            for (GuiModule module : category.modules) {
                if (mouseY >= cursorY && mouseY < cursorY + GuiTheme.MODULE_HEIGHT) {
                    if (mouseX < rowLeft + INDICATOR_INSET + INDICATOR_SIZE + 2) {
                        module.toggle();
                        this.dirty = true;
                    } else {
                        module.expanded = !module.expanded;
                    }

                    return true;
                }

                cursorY += GuiTheme.MODULE_HEIGHT;

                if (module.expand < 0.5F) {
                    cursorY += module.settingsHeight();
                    continue;
                }

                for (GuiSetting setting : module.settings) {
                    if (mouseY >= cursorY && mouseY < cursorY + GuiTheme.SETTING_HEIGHT) {
                        if (setting.checkbox()) {
                            setting.toggle();
                            this.dirty = true;
                        } else {
                            this.dragging = (GuiSetting.Number) setting;
                            this.dragLeft = rowLeft + 8;
                            this.dragWidth = hit.rowWidth() - 8;
                            applyDrag(mouseX);
                        }

                        return true;
                    }

                    cursorY += GuiTheme.SETTING_HEIGHT;
                }
            }

            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (this.dragging != null) {
            applyDrag(mouseX);
            return true;
        }

        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.dragging != null) {
            this.dragging = null;
            saveIfDirty();
            return true;
        }

        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        for (Panel panel : layout()) {
            if (mouseX < panel.x() || mouseX >= panel.x() + panel.w()
                    || mouseY < panel.y() || mouseY >= panel.y() + panel.h()) {
                continue;
            }

            GuiCategory category = panel.category();
            float max = Math.max(0.0F, category.animatedContentHeight() - panel.contentHeight());
            category.scroll = Math.max(0.0F,
                    Math.min(max, category.scroll - (float) verticalAmount * 16.0F));
            return true;
        }

        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT || keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.close();
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void close() {
        saveIfDirty();
        super.close();
    }

    private void applyDrag(double mouseX) {
        if (this.dragging == null || this.dragWidth <= 0) {
            return;
        }

        this.dragging.setFromRatio((float) ((mouseX - this.dragLeft) / this.dragWidth));
        this.dirty = true;
    }

    private void saveIfDirty() {
        if (this.dirty) {
            EagleConfig.get().save();
            this.dirty = false;
        }
    }
}
