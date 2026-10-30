package com.dmod.eagle;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import com.dmod.eagle.gui.GuiTheme;
import com.dmod.eagle.gui.ToastManager;
import com.dmod.eagle.mixin.MinecraftClientSessionMixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.session.Session;

/**
 * Swaps the client session for an offline one with a name of your choosing.
 *
 * <p>The whole account is one field. {@code MinecraftClient.session} is what
 * {@code getSession()} returns, what the login handler sends, and what the integrated server builds
 * the host player from - so replacing it is the entire feature. The UUID is derived the same way the
 * game derives offline UUIDs, from {@code "OfflinePlayer:" + name}, so the name and the identity stay
 * consistent with each other.
 *
 * <p><b>Timing.</b> The session is read when a connection is set up, so a change lands on the
 * <em>next</em> world join or server connect, not on the one already running. That is a property of
 * the game, not a shortcut here.
 *
 * <p>The original session is captured before the first swap and put back when the custom name is
 * cleared, so a real Microsoft account is never lost.
 */
public final class AccountLogic {

    /** Vanilla usernames are 3 to 16 characters of {@code [A-Za-z0-9_]}. */
    private static final int MIN_NAME_LENGTH = 3;
    private static final int MAX_NAME_LENGTH = 16;

    /** The session the game started with, captured the first time we overwrite it. */
    private static Session vanillaSession;

    /** The custom name currently installed, or null when the vanilla session is in place. */
    private static String appliedName;

    private AccountLogic() {
    }

    /** The name the client is currently connecting with. */
    public static String currentName(MinecraftClient mc) {
        Session session = mc == null ? null : mc.getSession();
        return session == null ? "" : session.getUsername();
    }

    /** The custom name currently installed, or null when running on the real account. */
    public static String appliedCustomName() {
        return appliedName;
    }

    public static boolean isValidName(String name) {
        if (name == null) {
            return false;
        }

        int length = name.length();

        if (length < MIN_NAME_LENGTH || length > MAX_NAME_LENGTH) {
            return false;
        }

        for (int i = 0; i < length; i++) {
            char c = name.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_';

            if (!allowed) {
                return false;
            }
        }

        return true;
    }

    /** Driven from the end of every client tick; does nothing until the wanted name actually differs. */
    public static void onEndTick(MinecraftClient mc) {
        if (mc == null || mc.getSession() == null) {
            return;
        }

        EagleConfig cfg = EagleConfig.get();
        String target = cfg.customNameEnabled ? cfg.customName.trim() : "";

        if (target.equals(appliedName == null ? "" : appliedName)) {
            return;
        }

        if (!target.isEmpty() && !isValidName(target)) {
            // Invalid input is rejected by the command that sets it, so just hold the current state.
            return;
        }

        MinecraftClientSessionMixin accessor = (MinecraftClientSessionMixin) mc;

        if (vanillaSession == null) {
            vanillaSession = accessor.eagle$getSession();
        }

        if (target.isEmpty()) {
            accessor.eagle$setSession(vanillaSession);
            appliedName = null;
            EagleClient.LOGGER.info("[Eagle] account restored to {}", vanillaSession.getUsername());
            ToastManager.show("Offline Account",
                    "Disabled - back to " + vanillaSession.getUsername(),
                    GuiTheme.ACCENTS[2]);
            return;
        }

        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + target).getBytes(StandardCharsets.UTF_8));

        accessor.eagle$setSession(new Session(
                target,
                uuid,
                "0",
                Optional.empty(),
                Optional.empty(),
                Session.AccountType.LEGACY));

        appliedName = target;
        EagleClient.LOGGER.info("[Eagle] offline account name set to {} (uuid {})", target, uuid);
        ToastManager.show("Offline Account", "Enabled as " + target, GuiTheme.ACCENTS[1]);
    }

    /** Drops the cached state. The session in place is not touched - the tick handler owns that. */
    public static void reset() {
        appliedName = null;
    }
}
