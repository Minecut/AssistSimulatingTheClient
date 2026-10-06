package com.dmod.eagle.gui;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import net.minecraft.client.option.KeyBinding;

/** One module row in the click GUI, with its settings. */
public final class GuiModule {

    public final String name;
    /**
     * Supplies the key that toggles this module, shown on the right of the row. Resolved lazily
     * because the registry is built on first open, and the keybinds are registered during mod init
     * - capturing the binding itself would freeze in a null if the order ever changed.
     */
    private final Supplier<KeyBinding> keyBinding;
    public final List<GuiSetting> settings;

    private final BooleanSupplier enabled;
    private final Consumer<Boolean> setEnabled;
    private final Runnable onToggle;

    // ---- animation state, owned by the screen
    public boolean expanded;
    public float expand;
    public float hover;

    public GuiModule(String name, Supplier<KeyBinding> keyBinding,
                     BooleanSupplier enabled, Consumer<Boolean> setEnabled, Runnable onToggle,
                     List<GuiSetting> settings) {
        this.name = name;
        this.keyBinding = keyBinding;
        this.enabled = enabled;
        this.setEnabled = setEnabled;
        this.onToggle = onToggle;
        this.settings = settings;
    }

    public boolean isEnabled() {
        return this.enabled.getAsBoolean();
    }

    public void toggle() {
        this.setEnabled.accept(!isEnabled());

        if (this.onToggle != null) {
            this.onToggle.run();
        }
    }

    /** Height of the settings block right now, animation included. */
    public int settingsHeight() {
        return Math.round(this.settings.size() * GuiTheme.SETTING_HEIGHT * this.expand);
    }

    /** Height of this entry right now, animation included. */
    public int currentHeight() {
        return GuiTheme.MODULE_HEIGHT + settingsHeight();
    }

    /** Height this entry occupies when fully expanded, used for scroll clamping. */
    public int fullHeight() {
        return GuiTheme.MODULE_HEIGHT + this.settings.size() * GuiTheme.SETTING_HEIGHT;
    }

    public String keyLabel() {
        KeyBinding binding = this.keyBinding == null ? null : this.keyBinding.get();
        return binding == null ? "" : binding.getBoundKeyLocalizedText().getString();
    }
}
