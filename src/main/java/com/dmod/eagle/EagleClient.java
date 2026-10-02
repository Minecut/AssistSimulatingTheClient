package com.dmod.eagle;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudLayerRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.IdentifiedLayer;
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

        ClientTickEvents.START_CLIENT_TICK.register(SafePadLogic::onStartTick);
        ClientTickEvents.END_CLIENT_TICK.register(this::onEndClientTick);

        HudLayerRegistrationCallback.EVENT.register(drawer -> drawer.addLayer(
                IdentifiedLayer.of(
                        Identifier.of(MOD_ID, "hud"),
                        (DrawContext context, RenderTickCounter tickCounter) -> EagleHud.render(context))));

        LOGGER.info("[Eagle] ready (V = eagle, B = safety pad, R = aim assist)");
    }

    private void onEndClientTick(MinecraftClient mc) {
        boolean changed = false;
        EagleConfig cfg = EagleConfig.get();

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

        if (changed) {
            cfg.save();
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
