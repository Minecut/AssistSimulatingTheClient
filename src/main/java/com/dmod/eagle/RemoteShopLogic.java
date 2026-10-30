package com.dmod.eagle;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.ShulkerBoxScreen;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.text.Text;

/**
 * Keeps a container alive after you close its window.
 *
 * <p>Vanilla's close path is short and very interceptable:
 *
 * <pre>
 *   HandledScreen.close()
 *     → client.player.closeHandledScreen()   // sends CloseHandledScreenC2SPacket, resets the handler
 *     → Screen.close() → setScreen(null)     // hides the window
 * </pre>
 *
 * <p>Swallowing that call at its head and clearing the screen by hand gives exactly the two halves
 * this needs: the server is never told the container closed, and the GUI disappears. The
 * {@link ScreenHandler} keeps its sync id, so reopening with the same handler later is a normal
 * screen over a live container.
 *
 * <p><b>What this can and cannot do.</b> Everything here is client side. Whether the container is
 * still open on the other end is the server's call - plenty of plugins close an inventory when the
 * player walks away or leaves a region. If that happens the client notices the handler was swapped
 * out and silently drops the stash rather than showing a ghost window.
 */
public final class RemoteShopLogic {

    private static ScreenHandler stash;
    private static Text stashTitle = Text.empty();

    private RemoteShopLogic() {
    }

    public static boolean hasStash() {
        return stash != null;
    }

    public static String stashName() {
        return stashTitle.getString();
    }

    public static void reset() {
        stash = null;
        stashTitle = Text.empty();
    }

    /**
     * Called at the head of {@code HandledScreen.close()}.
     *
     * @return {@code true} when the close should be swallowed and the window simply hidden
     */
    public static boolean stash(HandledScreen<?> screen) {
        if (!EagleConfig.get().remoteShopEnabled) {
            return false;
        }

        ScreenHandler handler = screen.getScreenHandler();

        if (!isSupported(handler)) {
            return false;
        }

        stash = handler;
        stashTitle = screen.getTitle();
        return true;
    }

    /** The shop is a chest or a shulker box; other containers keep their normal close behaviour. */
    private static boolean isSupported(ScreenHandler handler) {
        return handler instanceof GenericContainerScreenHandler
                || handler instanceof ShulkerBoxScreenHandler;
    }

    /** Rebuilds a window over the stashed handler. Returns false when there is nothing to show. */
    public static boolean reopen(MinecraftClient mc) {
        if (stash == null || mc == null || mc.player == null) {
            return false;
        }

        Screen screen;

        if (stash instanceof GenericContainerScreenHandler generic) {
            screen = new GenericContainerScreen(generic, mc.player.getInventory(), stashTitle);
        } else if (stash instanceof ShulkerBoxScreenHandler shulker) {
            screen = new ShulkerBoxScreen(shulker, mc.player.getInventory(), stashTitle);
        } else {
            reset();
            return false;
        }

        mc.setScreen(screen);
        return true;
    }

    /**
     * Drops the stash the moment the game no longer holds that handler.
     *
     * <p>That covers both endings: the server closing the container (the client's handler is reset to
     * the player's own) and the player opening something else (the handler is replaced). Either way
     * the stored one is stale and showing it would only produce clicks the server throws away.
     */
    public static void onEndTick(MinecraftClient mc) {
        if (stash == null) {
            return;
        }

        if (!EagleConfig.get().remoteShopEnabled
                || mc == null
                || mc.player == null
                || mc.world == null
                || mc.player.currentScreenHandler != stash) {
            reset();
        }
    }
}
