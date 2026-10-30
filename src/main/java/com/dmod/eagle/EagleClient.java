package com.dmod.eagle;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dmod.eagle.gui.DynamicIsland;
import com.dmod.eagle.gui.EagleScreen;
import com.dmod.eagle.gui.ToastManager;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
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
    private static KeyBinding autoArmourToggleKey;
    private static KeyBinding aimToggleKey;
    private static KeyBinding invChestToggleKey;
    private static KeyBinding espToggleKey;
    private static KeyBinding blockInToggleKey;
    private static KeyBinding remoteShopKey;
    private static KeyBinding guiKey;

    /**
     * Latches the GUI key so a press that is consumed while the screen is open cannot immediately
     * reopen it. Re-armed only once the key is physically released.
     */
    private static boolean guiLatched;

    public static KeyBinding keyEagle() {
        return toggleKey;
    }

    public static KeyBinding keyAutoArmour() {
        return autoArmourToggleKey;
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

    public static KeyBinding keyRemoteShop() {
        return remoteShopKey;
    }

    @Override
    public void onInitializeClient() {
        EagleConfig.get();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.eagle.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                "category.eagle"));

        autoArmourToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.eagle.autoarmour",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F6,
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

        remoteShopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.eagle.remoteshop",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                "category.eagle"));

        ClientTickEvents.END_CLIENT_TICK.register(InvChestLogic::onEndTick);
        ClientTickEvents.END_CLIENT_TICK.register(AutoArmourLogic::onEndTick);
        ClientTickEvents.END_CLIENT_TICK.register(AccountLogic::onEndTick);
        ClientTickEvents.END_CLIENT_TICK.register(RemoteShopLogic::onEndTick);
        ClientTickEvents.END_CLIENT_TICK.register(EspLogic::onEndTick);
        ClientTickEvents.END_CLIENT_TICK.register(BlockInLogic::onEndTick);
        ClientTickEvents.END_CLIENT_TICK.register(this::onEndClientTick);

        WorldRenderEvents.AFTER_ENTITIES.register(EspRenderer::render);

        HudLayerRegistrationCallback.EVENT.register(drawer -> drawer.addLayer(
                IdentifiedLayer.of(
                        Identifier.of(MOD_ID, "hud"),
                        (DrawContext context, RenderTickCounter tickCounter) -> EagleHud.render(context))));

        HudLayerRegistrationCallback.EVENT.register(drawer -> drawer.addLayer(
                IdentifiedLayer.of(
                        Identifier.of(MOD_ID, "toast"),
                        (DrawContext context, RenderTickCounter tickCounter) -> ToastManager.renderHud(context))));

        HudLayerRegistrationCallback.EVENT.register(drawer -> drawer.addLayer(
                IdentifiedLayer.of(
                        Identifier.of(MOD_ID, "island"),
                        (DrawContext context, RenderTickCounter tickCounter) -> DynamicIsland.renderHud(context))));

        // Screens draw over the HUD, so the notice needs a second home on top of whatever is open.
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) ->
                ScreenEvents.afterRender(screen).register((rendered, context, mouseX, mouseY, tickDelta) -> {
                    DynamicIsland.renderScreen(context);
                    ToastManager.renderScreen(context);
                }));

        LOGGER.info("[Eagle] ready (V = eagle, F6 = auto armour, R = aim assist, N = chest looter, "
                + "G = esp, K = block in, H = remote shop, Right Shift = GUI)");
    }

    private void onEndClientTick(MinecraftClient mc) {
        boolean changed = false;
        EagleConfig cfg = EagleConfig.get();

        handleGuiKey(mc);

        // H brings the stashed container back. Presses are always drained, so one that lands while a
        // window is already open cannot fire later when it closes.
        while (remoteShopKey.wasPressed()) {
            if (mc.currentScreen == null) {
                RemoteShopLogic.reopen(mc);
            }
        }

        while (toggleKey.wasPressed()) {
            cfg.enabled = !cfg.enabled;
            EagleLogic.reset();
            changed = true;
            notify(mc, "Eagle", cfg.enabled);
        }

        while (autoArmourToggleKey.wasPressed()) {
            cfg.autoArmourEnabled = !cfg.autoArmourEnabled;
            AutoArmourLogic.reset();
            changed = true;
            notify(mc, "AutoArmour", cfg.autoArmourEnabled);
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
        // The island announcement rides along with the chat line, so every keybind toggle gets it
        // from one place instead of six.
        DynamicIsland.flash(module, on);

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
