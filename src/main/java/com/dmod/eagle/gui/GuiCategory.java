package com.dmod.eagle.gui;

import java.util.ArrayList;
import java.util.List;

import com.dmod.eagle.AimAssistLogic;
import com.dmod.eagle.BlockInLogic;
import com.dmod.eagle.EagleClient;
import com.dmod.eagle.EagleConfig;
import com.dmod.eagle.EagleLogic;
import com.dmod.eagle.EspLogic;
import com.dmod.eagle.InvChestLogic;
import com.dmod.eagle.SafePadLogic;

/** A column of the click GUI: a name, an accent colour, and its modules. */
public final class GuiCategory {

    public final String name;
    public final int accent;
    public final List<GuiModule> modules = new ArrayList<>();

    public float scroll;
    public float slide;
    public float hoverHeader;

    public GuiCategory(String name, int accent) {
        this.name = name;
        this.accent = accent;
    }

    /** Unclipped height of everything below the header. */
    public int contentHeight() {
        int total = GuiTheme.PADDING;

        for (GuiModule module : this.modules) {
            total += module.fullHeight();
        }

        return total + GuiTheme.PADDING;
    }

    /** Same, but accounting for the expand animation, which is what is actually drawn. */
    public int animatedContentHeight() {
        int total = GuiTheme.PADDING;

        for (GuiModule module : this.modules) {
            total += module.currentHeight();
        }

        return total + GuiTheme.PADDING;
    }

    // ------------------------------------------------------------------ registry

    /**
     * Builds the four panels.
     *
     * <p>Every setting reads and writes {@link EagleConfig} directly, so nothing here needs syncing
     * with the keybinds or the chat command - they all drive the same fields.
     */
    public static List<GuiCategory> buildAll() {
        EagleConfig cfg = EagleConfig.get();

        GuiCategory movement = new GuiCategory("Movement", GuiTheme.ACCENTS[0]);
        GuiCategory combat = new GuiCategory("Combat", GuiTheme.ACCENTS[1]);
        GuiCategory player = new GuiCategory("Player", GuiTheme.ACCENTS[2]);
        GuiCategory render = new GuiCategory("Render", GuiTheme.ACCENTS[3]);

        // ------------------------------------------------------------------ Eagle
        movement.modules.add(new GuiModule(
                "Eagle", EagleClient::keyEagle,
                () -> cfg.enabled, value -> cfg.enabled = value, EagleLogic::reset,
                List.of(
                        number("Edge Offset", 0.0D, 0.30D, 2, () -> cfg.edgeOffset, v -> cfg.edgeOffset = v),
                        number("Release Margin", 0.0D, 0.30D, 2, () -> cfg.releaseMargin, v -> cfg.releaseMargin = v),
                        bool("Keep Sneak Idle", () -> cfg.keepSneakWhileIdle, v -> cfg.keepSneakWhileIdle = v),
                        number("Sneak Delay", 0.0D, 200.0D, 0, () -> cfg.sneakDelayMs, v -> cfg.sneakDelayMs = (int) v),
                        number("Release Delay", 0.0D, 1000.0D, 0, () -> cfg.releaseDelayMs, v -> cfg.releaseDelayMs = (int) v),
                        bool("Randomize Delay", () -> cfg.randomizeDelay, v -> cfg.randomizeDelay = v),
                        bool("Block In Hand", () -> cfg.onlyWhileBridging, v -> cfg.onlyWhileBridging = v),
                        bool("CPS Boost", () -> cfg.cpsBoostEnabled, v -> cfg.cpsBoostEnabled = v),
                        number("CPS Min", 1.0D, 20.0D, 1, () -> cfg.cpsMin, v -> cfg.cpsMin = v),
                        number("CPS Max", 1.0D, 20.0D, 1, () -> cfg.cpsMax, v -> cfg.cpsMax = v),
                        bool("Require Block", () -> cfg.cpsRequireBlock, v -> cfg.cpsRequireBlock = v))));

        // ------------------------------------------------------------------ SafePad
        movement.modules.add(new GuiModule(
                "SafePad", EagleClient::keyPad,
                () -> cfg.safePadEnabled, value -> cfg.safePadEnabled = value, SafePadLogic::reset,
                List.of(
                        number("Cooldown", 0.0D, 40.0D, 0, () -> cfg.safePadCooldownTicks, v -> cfg.safePadCooldownTicks = (int) v),
                        bool("Fall Rescue", () -> cfg.safePadFallRescue, v -> cfg.safePadFallRescue = v),
                        number("Rescue Fall Speed", 0.0D, 2.0D, 2, () -> cfg.safePadRescueMinFallSpeed, v -> cfg.safePadRescueMinFallSpeed = v))));

        // ------------------------------------------------------------------ AimAssist
        combat.modules.add(new GuiModule(
                "AimAssist", EagleClient::keyAim,
                () -> cfg.aimEnabled, value -> cfg.aimEnabled = value, AimAssistLogic::reset,
                List.of(
                        number("Max Angle", 1.0D, 180.0D, 0, () -> cfg.aimMaxAngle, v -> cfg.aimMaxAngle = v),
                        number("Distance", 1.0D, 32.0D, 1, () -> cfg.aimDistance, v -> cfg.aimDistance = v),
                        number("Horizontal Speed", 0.0D, 1.0D, 2, () -> cfg.aimHorizontalSpeed, v -> cfg.aimHorizontalSpeed = v),
                        number("Vertical Speed", 0.0D, 1.0D, 2, () -> cfg.aimVerticalSpeed, v -> cfg.aimVerticalSpeed = v),
                        number("Jitter", 0.0D, 5.0D, 2, () -> cfg.aimJitterDegrees, v -> cfg.aimJitterDegrees = v),
                        bool("Through Walls", () -> cfg.aimThroughWalls, v -> cfg.aimThroughWalls = v),
                        bool("Target Players", () -> cfg.aimTargetPlayers, v -> cfg.aimTargetPlayers = v),
                        bool("Target Mobs", () -> cfg.aimTargetMobs, v -> cfg.aimTargetMobs = v),
                        bool("Click Only", () -> cfg.aimClickOnly, v -> cfg.aimClickOnly = v),
                        bool("Weapon Only", () -> cfg.aimWeaponOnly, v -> cfg.aimWeaponOnly = v),
                        bool("Strafe Increase", () -> cfg.aimStrafeIncrease, v -> cfg.aimStrafeIncrease = v),
                        bool("Sensitivity Sync", () -> cfg.aimSensitivitySync, v -> cfg.aimSensitivitySync = v))));

        // ------------------------------------------------------------------ InvChest
        player.modules.add(new GuiModule(
                "InvChest", EagleClient::keyInvChest,
                () -> cfg.invChestEnabled, value -> cfg.invChestEnabled = value, InvChestLogic::reset,
                List.of(
                        number("Delay", 50.0D, 10000.0D, 0, () -> cfg.invChestDelayMs, v -> cfg.invChestDelayMs = (int) v),
                        number("Min Tier", 1.0D, 5.0D, 0, () -> cfg.invChestMinTier, v -> cfg.invChestMinTier = (int) v),
                        bool("Skip Existing", () -> cfg.invChestSkipExisting, v -> cfg.invChestSkipExisting = v),
                        bool("Close When Done", () -> cfg.invChestCloseWhenDone, v -> cfg.invChestCloseWhenDone = v))));

        // ------------------------------------------------------------------ BlockIn
        player.modules.add(new GuiModule(
                "BlockIn", EagleClient::keyBlockIn,
                () -> cfg.blockInEnabled, value -> cfg.blockInEnabled = value, BlockInLogic::reset,
                List.of(
                        number("Layers", 1.0D, 2.0D, 0, () -> cfg.blockInLayers, v -> cfg.blockInLayers = (int) v),
                        number("Delay", 0.0D, 1000.0D, 0, () -> cfg.blockInDelayMs, v -> cfg.blockInDelayMs = (int) v),
                        number("Turn Speed", 1.0D, 180.0D, 0, () -> cfg.blockInRotationSpeed, v -> cfg.blockInRotationSpeed = v),
                        bool("Corners", () -> cfg.blockInCorners, v -> cfg.blockInCorners = v),
                        bool("Visible Turn", () -> cfg.blockInVisibleRotation, v -> cfg.blockInVisibleRotation = v),
                        bool("Return View", () -> cfg.blockInReturnRotation, v -> cfg.blockInReturnRotation = v))));

        // ------------------------------------------------------------------ ESP
        render.modules.add(new GuiModule(
                "ESP", EagleClient::keyEsp,
                () -> cfg.espEnabled, value -> cfg.espEnabled = value, EspLogic::reset,
                List.of(
                        number("Range", 4.0D, 256.0D, 0, () -> cfg.espRange, v -> cfg.espRange = v),
                        number("Refresh Ticks", 1.0D, 40.0D, 0, () -> cfg.espRefreshTicks, v -> cfg.espRefreshTicks = (int) v),
                        bool("Glow Outline", () -> cfg.espGlow, v -> cfg.espGlow = v),
                        bool("Names", () -> cfg.espShowNames, v -> cfg.espShowNames = v),
                        bool("Show Distance", () -> cfg.espShowDistance, v -> cfg.espShowDistance = v),
                        bool("Target Players", () -> cfg.espTargetPlayers, v -> cfg.espTargetPlayers = v),
                        bool("Target Mobs", () -> cfg.espTargetMobs, v -> cfg.espTargetMobs = v))));

        List<GuiCategory> categories = new ArrayList<>();
        categories.add(movement);
        categories.add(combat);
        categories.add(player);
        categories.add(render);
        return categories;
    }

    private static GuiSetting bool(String name, java.util.function.Supplier<Boolean> getter,
                                   java.util.function.Consumer<Boolean> setter) {
        return new GuiSetting.Bool(name, getter::get, setter);
    }

    private static GuiSetting number(String name, double min, double max, int decimals,
                                     java.util.function.DoubleSupplier getter,
                                     java.util.function.DoubleConsumer setter) {
        return new GuiSetting.Number(name, min, max, decimals, getter, setter);
    }
}
