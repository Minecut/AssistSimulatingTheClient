package com.dmod.eagle;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dmod.eagle.gui.EagleScreen;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

public class EagleClient implements ClientModInitializer {

    public static final String MOD_ID = "eagle";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static KeyBinding toggleKey;
    private static KeyBinding padToggleKey;
    private static KeyBinding aimToggleKey;
    private static KeyBinding invChestToggleKey;
    private static KeyBinding espToggleKey;
    private static KeyBinding blockInToggleKey;
    private static KeyBinding guiKey;

    /**
     * Latches the GUI key so a press that is consumed while the screen is open cannot immediately
     * reopen it. Re-armed only once the key is physically released.
     */
    private static boolean guiLatched;

    public static KeyBinding keyEagle() {
        return toggleKey;
    }

    public static KeyBinding keyPad() {
        return padToggleKey;
    }

    public static KeyBinding keyAim() {
        return aimToggleKey;
    }

    public static KeyBinding keyInvChest() {
        return invChestToggleKey;
    }

    public static KeyBinding keyEsp() {
        return espToggleKey;
    }

    public static KeyBinding keyBlockIn() {
        return blockInToggleKey;
    }

    @Override
    public void onInitializeClient() {
        EagleConfig.get();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.eagle.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                "category.eagle"));

        padToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.eagle.pad",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                "category.eagle"));

        aimToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.eagle.aim",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                "category.eagle"));

        invChestToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.eagle.invchest",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_N,
                "category.eagle"));

        espToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.eagle.esp",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                "category.eagle"));

        guiKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.eagle.gui",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_SHIFT,
                "category.eagle"));

        blockInToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.eagle.blockin",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "category.eagle"));

        ClientTickEvents.START_CLIENT_TICK.register(SafePadLogic::onStartTick);
        ClientTickEvents.END_CLIENT_TICK.register(InvChestLogic::onEndTick);
        ClientTickEvents.END_CLIENT_TICK.register(EspLogic::onEndTick);
        ClientTickEvents.END_CLIENT_TICK.register(BlockInLogic::onEndTick);
        ClientTickEvents.END_CLIENT_TICK.register(this::onEndClientTick);

        WorldRenderEvents.AFTER_ENTITIES.register(EspRenderer::render);

        HudLayerRegistrationCallback.EVENT.register(drawer -> drawer.addLayer(
                IdentifiedLayer.of(
                        Identifier.of(MOD_ID, "hud"),
                        (DrawContext context, RenderTickCounter tickCounter) -> EagleHud.render(context))));

        LOGGER.info("[Eagle] ready (V = eagle, B = safety pad, R = aim assist, N = chest looter, "
                + "G = esp, K = block in, Right Shift = GUI)");
    }

    private void onEndClientTick(MinecraftClient mc) {
        boolean changed = false;
        EagleConfig cfg = EagleConfig.get();

        handleGuiKey(mc);

        while (toggleKey.wasPressed()) {
            cfg.enabled = !cfg.enabled;
            EagleLogic.reset();
            changed = true;
            notify(mc, "Eagle", cfg.enabled);
        }

        while (padToggleKey.wasPressed()) {
            cfg.safePadEnabled = !cfg.safePadEnabled;
            SafePadLogic.reset();
            changed = true;
            notify(mc, "SafePad", cfg.safePadEnabled);
        }

        while (aimToggleKey.wasPressed()) {
            cfg.aimEnabled = !cfg.aimEnabled;
            AimAssistLogic.reset();
            changed = true;
            notify(mc, "AimAssist", cfg.aimEnabled);
        }

        while (invChestToggleKey.wasPressed()) {
            cfg.invChestEnabled = !cfg.invChestEnabled;
            InvChestLogic.reset();
            changed = true;
            notify(mc, "InvChest", cfg.invChestEnabled);
        }

        while (espToggleKey.wasPressed()) {
            cfg.espEnabled = !cfg.espEnabled;
            EspLogic.reset();
            changed = true;
            notify(mc, "ESP", cfg.espEnabled);
        }

        while (blockInToggleKey.wasPressed()) {
            cfg.blockInEnabled = !cfg.blockInEnabled;
            BlockInLogic.reset();
            changed = true;
            notify(mc, "BlockIn", cfg.blockInEnabled);
        }

        if (changed) {
            cfg.save();
        }
    }

    /**
     * Opens the click GUI.
     *
     * <p>Edge detection is done on {@code isPressed()} rather than {@code wasPressed()}, because a
     * press that arrives while the GUI is already up would otherwise still be sitting in the key's
     * counter when the screen closes - and reopen it on the very next tick. The latch clears only
     * once the key is physically released.
     */
    private static void handleGuiKey(MinecraftClient mc) {
        boolean down = guiKey.isPressed();
        boolean guiOpen = mc.currentScreen instanceof EagleScreen;

        if (guiLatched) {
            if (!down) {
                guiLatched = false;
            }
            return;
        }

        if (down && !guiOpen) {
            guiLatched = true;
            mc.setScreen(new EagleScreen());
        }
    }

    private static void notify(MinecraftClient mc, String module, boolean on) {
        if (mc.player == null) {
            return;
        }

        MutableText message = Text.literal("[Eagle] " + module + " ")
                .formatted(Formatting.AQUA)
                .append(Text.literal(on ? "ON" : "OFF")
                        .formatted(on ? Formatting.GREEN : Formatting.RED));

        mc.player.sendMessage(message, true);
    }
}
