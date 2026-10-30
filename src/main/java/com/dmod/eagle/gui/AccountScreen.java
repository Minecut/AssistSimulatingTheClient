package com.dmod.eagle.gui;

import com.dmod.eagle.AccountLogic;
import com.dmod.eagle.EagleConfig;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * Reached from the button on the multiplayer screen.
 *
 * <p>Typing is the only way to enter a name, which is why this exists rather than a chat command: the
 * account the client connects with is a multiplayer concern, so the switch lives where you choose a
 * server. The text field's predicate blocks anything the game would reject before it can be typed, so
 * the name is valid the moment it appears.
 *
 * <p>Nothing here touches the session directly. Flipping the flag is enough - the tick handler in
 * {@link AccountLogic} notices and performs the swap, which means this screen, a hand-edited config
 * file and anything added later all go through exactly one code path.
 */
public class AccountScreen extends Screen {

    private final Screen parent;

    private TextFieldWidget nameField;
    private String status = "";
    private int statusColor = 0xFF9A9AA6;

    public AccountScreen(Screen parent) {
        super(Text.literal("Offline Account"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        EagleConfig cfg = EagleConfig.get();

        int centerX = this.width / 2;
        int top = this.height / 2 - 52;

        this.nameField = new TextFieldWidget(this.textRenderer, centerX - 100, top + 24, 200, 20,
                Text.literal("Name"));
        this.nameField.setMaxLength(16);
        this.nameField.setText(cfg.customName);
        this.nameField.setTextPredicate(name -> name.isEmpty() || AccountLogic.isValidName(name));
        addDrawableChild(this.nameField);
        setInitialFocus(this.nameField);

        addDrawableChild(ButtonWidget.builder(Text.literal("Enable"), button -> apply())
                .dimensions(centerX - 100, top + 52, 64, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Original"), button -> useOriginal())
                .dimensions(centerX - 32, top + 52, 64, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), button -> close())
                .dimensions(centerX + 36, top + 52, 64, 20).build());
    }

    private void apply() {
        String name = this.nameField.getText().trim();

        if (!AccountLogic.isValidName(name)) {
            this.status = "3 to 16 letters, digits or underscores";
            this.statusColor = 0xFFFF6060;
            return;
        }

        EagleConfig cfg = EagleConfig.get();
        cfg.customName = name;
        cfg.customNameEnabled = true;
        cfg.save();

        this.status = "Enabled - takes effect on the next connection";
        this.statusColor = 0xFF54E08A;
    }

    private void useOriginal() {
        EagleConfig cfg = EagleConfig.get();
        cfg.customNameEnabled = false;
        cfg.save();

        this.status = "Disabled - the original account returns";
        this.statusColor = 0xFFFFC04C;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Screen.render draws the background and every child widget for us.
        super.render(context, mouseX, mouseY, delta);

        int centerX = this.width / 2;
        int top = this.height / 2 - 52;

        drawCentred(context, this.title.getString(), top - 6, 0xFFFFFFFF);

        String custom = EagleConfig.get().customName;
        String current = "session: " + AccountLogic.currentName(this.client)
                + (custom.isEmpty() ? "" : "   configured: " + custom);
        drawCentred(context, current, top + 82, 0xFF9A9AA6);

        if (!this.status.isEmpty()) {
            drawCentred(context, this.status, top + 96, this.statusColor);
        }
    }

    private void drawCentred(DrawContext context, String text, int y, int color) {
        context.drawText(this.textRenderer, text,
                (this.width - this.textRenderer.getWidth(text)) / 2, y, color, true);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }
}
