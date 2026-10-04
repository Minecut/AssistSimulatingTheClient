package com.dmod.eagle;

import java.util.Locale;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Client side chat commands, typed into the normal chat box with a leading dot.
 *
 * <p>The interception lives in {@link com.dmod.eagle.mixin.ChatCommandMixin}, on
 * {@code ClientPlayNetworkHandler#sendChatMessage}. Cancelling there means the text never becomes a
 * packet - the server sees nothing at all, and the reply is written straight into the local chat HUD
 * with {@code ChatHud#addMessage}, which is a client-only call.
 *
 * <p>Only commands this class actually owns are swallowed. A message like {@code .hello} is not one
 * of ours, so it travels to the server as ordinary chat rather than vanishing.
 */
public final class EagleCommands {

    private static final String PREFIX = ".";

    /** Every command root this mod owns. Anything else falls through to the server. */
    private static final String[] ROOTS = { "inv" };

    private EagleCommands() {
    }

    /**
     * @return {@code true} when the message was one of ours and must not reach the server
     */
    public static boolean handle(MinecraftClient mc, String message) {
        if (mc == null || message == null) {
            return false;
        }

        String trimmed = message.trim();

        if (!trimmed.startsWith(PREFIX)) {
            return false;
        }

        String body = trimmed.substring(PREFIX.length()).trim();

        if (body.isEmpty()) {
            return false;
        }

        String[] parts = body.split("\\s+");
        boolean ours = false;

        for (String root : ROOTS) {
            if (parts[0].equalsIgnoreCase(root)) {
                ours = true;
                break;
            }
        }

        if (!ours) {
            return false;
        }

        if (parts[0].equalsIgnoreCase("inv")) {
            runInvChest(mc, parts);
        }

        return true;
    }

    // ------------------------------------------------------------------ .inv

    private static void runInvChest(MinecraftClient mc, String[] parts) {
        EagleConfig cfg = EagleConfig.get();

        if (parts.length == 1) {
            info(mc, "用法: .inv speed <秒>");
            info(mc, "当前 " + describe(cfg.invChestDelayMs));
            return;
        }

        if (!parts[1].equalsIgnoreCase("speed")) {
            error(mc, "未知参数 '" + parts[1] + "'，用法: .inv speed <秒>");
            return;
        }

        if (parts.length < 3) {
            info(mc, "用法: .inv speed <秒>，例如 .inv speed 0.85");
            info(mc, "当前 " + describe(cfg.invChestDelayMs));
            return;
        }

        double seconds;

        try {
            seconds = Double.parseDouble(parts[2]);
        } catch (NumberFormatException e) {
            error(mc, "'" + parts[2] + "' 不是有效数字，例如 .inv speed 0.85");
            return;
        }

        if (!Double.isFinite(seconds) || seconds <= 0.0D) {
            error(mc, "间隔必须是一个大于 0 的数字");
            return;
        }

        long requestedMs = Math.round(seconds * 1000.0D);
        int appliedMs = (int) Math.max(EagleConfig.INV_CHEST_MIN_DELAY_MS,
                Math.min(EagleConfig.INV_CHEST_MAX_DELAY_MS, requestedMs));

        cfg.invChestDelayMs = appliedMs;
        cfg.save();

        info(mc, "InvChest " + describe(appliedMs));

        if (appliedMs != requestedMs) {
            info(mc, "超出允许范围，已钳制到 "
                    + human(EagleConfig.INV_CHEST_MIN_DELAY_MS / 1000.0D) + " ~ "
                    + human(EagleConfig.INV_CHEST_MAX_DELAY_MS / 1000.0D) + " 秒");
        }
    }

    // ------------------------------------------------------------------ output

    /** Renders a delay both ways, so there is never any doubt which one the number means. */
    private static String describe(int delayMs) {
        return "间隔 " + human(delayMs / 1000.0D) + " 秒/件（约 "
                + human(1000.0D / delayMs) + " 件/秒）";
    }

    private static void info(MinecraftClient mc, String text) {
        send(mc, text, Formatting.GRAY);
    }

    private static void error(MinecraftClient mc, String text) {
        send(mc, text, Formatting.RED);
    }

    private static void send(MinecraftClient mc, String text, Formatting colour) {
        if (mc.inGameHud == null) {
            return;
        }

        MutableText message = Text.literal("[Eagle] ")
                .formatted(Formatting.AQUA)
                .append(Text.literal(text).formatted(colour));

        // Client-only: writes into the local chat HUD and sends nothing.
        mc.inGameHud.getChatHud().addMessage(message);
    }

    /** Up to three decimals, with the trailing zeros trimmed so 1.000 reads as 1. */
    private static String human(double value) {
        String text = String.format(Locale.ROOT, "%.3f", value);

        if (text.indexOf('.') >= 0) {
            text = text.replaceAll("0+$", "");

            if (text.endsWith(".")) {
                text = text.substring(0, text.length() - 1);
            }
        }

        return text;
    }
}
