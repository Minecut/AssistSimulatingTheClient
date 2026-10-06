package com.dmod.eagle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Plain JSON config, stored in {@code .minecraft/config/eagle.json}.
 *
 * <p>Every field is user editable while the game is closed. The file is rewritten whenever the
 * module is toggled, so hand edits survive as long as the game is not running.
 */
public final class EagleConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("eagle.json");

    private static EagleConfig instance;

    // ------------------------------------------------------------------ options

    /** Master switch, also bound to the {@code V} key in game. */
    public boolean enabled = true;

    /**
     * How far in front of the player's centre the ground probe is placed, on top of the vanilla
     * half width of 0.3. Larger = sneak earlier = safer but more conspicuous.
     */
    public double edgeOffset = 0.08D;

    /**
     * Hysteresis. Once sneaking, the player only stops sneaking after the probe moved this much
     * further back towards the body, which prevents sneak flicker on block seams.
     */
    public double releaseMargin = 0.06D;

    /**
     * Keep holding sneak when the player stops moving with the edge still in front of them.
     * Turning it off makes the module drop sneak the moment movement input is released.
     */
    public boolean keepSneakWhileIdle = true;

    /** Artificial delay before the sneak is issued after an edge was detected. Keep it small. */
    public int sneakDelayMs = 0;

    /** Artificial delay before the sneak is released after the edge cleared. */
    public int releaseDelayMs = 40;

    /** Randomise both delays between 0 and their configured value, to avoid a fixed timing pattern. */
    public boolean randomizeDelay = true;

    /** Only act while holding a placeable block, i.e. while actually bridging. */
    public boolean onlyWhileBridging = false;

    /** Draw the small state readout in the top left corner. */
    public boolean showHud = true;

    // ------------------------------------------------------------------ right click pacing

    /**
     * Sub-switch of the Eagle module for the held right-click booster. Still requires
     * {@link #enabled}, because pressing V turns the whole module off.
     */
    public boolean cpsBoostEnabled = true;

    /** Lower end of the target click rate, in clicks per second. */
    public double cpsMin = 6.0D;

    /** Upper end of the target click rate, in clicks per second. */
    public double cpsMax = 9.0D;

    /**
     * Only pace while the main hand holds a placeable block, i.e. while actually bridging. The
     * booster never clicks on its own in any case, this just narrows when it joins in.
     */
    public boolean cpsRequireBlock = true;

    // ------------------------------------------------------------------ safe pad

    /**
     * Master switch of the safety pad. Off by default, because it is the only part of this mod that
     * changes the world, so enabling it should be an explicit choice. Bound to {@code B} in game.
     */
    public boolean safePadEnabled = false;

    /** Ticks to wait between two pad placements, so a failing placement cannot spam packets. */
    public int safePadCooldownTicks = 2;

    /** Also catch the player once already airborne and falling, by padding straight underneath. */
    public boolean safePadFallRescue = true;

    /** Minimum downward speed, in blocks per tick, before the fall rescue engages. */
    public double safePadRescueMinFallSpeed = 0.08D;

    // ------------------------------------------------------------------ aim assist

    /**
     * Master switch of the aim assist. Independent of {@link #enabled}: Eagle is movement and
     * bridging, this is combat, and they are useful separately. Bound to {@code R} in game.
     */
    public boolean aimEnabled = false;

    /** Half angle of the acquisition cone, in degrees. Nothing outside this is ever picked up. */
    public double aimMaxAngle = 90.0D;

    /** Maximum distance to a target, in blocks. */
    public double aimDistance = 4.5D;

    /** Keep tracking targets that are behind walls. */
    public boolean aimThroughWalls = false;

    /** Consider other players. */
    public boolean aimTargetPlayers = true;

    /** Consider mobs. */
    public boolean aimTargetMobs = false;

    /** Only assist while the attack button is held. Off means assist whenever a target is in view. */
    public boolean aimClickOnly = true;

    /** Only assist while the main hand holds a sword or an axe. */
    public boolean aimWeaponOnly = false;

    /** Fraction of the remaining horizontal error closed per 50 ms. 0 disables, 1 snaps. */
    public double aimHorizontalSpeed = 0.35D;

    /** Fraction of the remaining vertical error closed per 50 ms. Kept lower to hold the head line. */
    public double aimVerticalSpeed = 0.28D;

    /** Speed the assist up while the player is moving. */
    public boolean aimStrafeIncrease = true;

    /**
     * Scale the correction with the player's mouse sensitivity, the way the assist is normally
     * described. The multiplier is {@code (1.2 * sensitivity + 0.4)^3}, which is 1.0 at the default
     * sensitivity of 50% and about 4.1 at 100%. Turn it off for a fixed strength.
     */
    public boolean aimSensitivitySync = true;

    /** Angular radius of the slow random wobble added to the aim point, in degrees. */
    public double aimJitterDegrees = 0.6D;

    /** Player names to leave alone, matched case insensitively. */
    public List<String> aimWhitelist = new ArrayList<>();

    // ------------------------------------------------------------------ chest looting

    /**
     * Master switch of the chest looter. Independent of everything else. Bound to {@code N} in game.
     */
    public boolean invChestEnabled = false;

    /** Lower bound for {@link #invChestDelayMs}, shared with the {@code .inv speed} command. */
    public static final int INV_CHEST_MIN_DELAY_MS = 50;

    /** Upper bound for {@link #invChestDelayMs}, shared with the {@code .inv speed} command. */
    public static final int INV_CHEST_MAX_DELAY_MS = 10000;

    /** Milliseconds between two takes. 850 ms is roughly a person emptying a chest by hand. */
    public int invChestDelayMs = 850;

    /** Skip anything the player already carries somewhere in their inventory. */
    public boolean invChestSkipExisting = true;

    /** Lowest tier worth taking, 1..5. Raise it to leave the junk behind. */
    public int invChestMinTier = 1;

    /** Close the container once nothing is left to take. */
    public boolean invChestCloseWhenDone = false;

    // ------------------------------------------------------------------ esp

    /** Master switch of the ESP. Independent of everything else. Bound to {@code G} in game. */
    public boolean espEnabled = false;

    /** Maximum distance to highlight, in blocks. */
    public double espRange = 64.0D;

    /** Draw the vanilla glowing outline, which shows through terrain. */
    public boolean espGlow = true;

    /** Replace the name label above tracked entities. */
    public boolean espShowNames = true;

    /** Append the distance to the label. */
    public boolean espShowDistance = true;

    /** Consider mobs as well as players. */
    public boolean espTargetMobs = false;

    /** Consider other players. */
    public boolean espTargetPlayers = true;

    /**
     * Ticks between rebuilds of the tracked set. Everything expensive - the entity scan, the label
     * text allocation, the glow flag writes - happens on this timer rather than every frame.
     */
    public int espRefreshTicks = 4;

    // ------------------------------------------------------------------ block in

    /** Master switch of BlockIn. Independent of everything else. Bound to {@code K} in game. */
    public boolean blockInEnabled = false;

    /** How tall the wall is: 1 fills beside the feet, 2 adds a row at head height. */
    public int blockInLayers = 1;

    /** Also fill the four diagonals. */
    public boolean blockInCorners = false;

    /** Milliseconds between two placements. */
    public int blockInDelayMs = 60;

    /** How fast the aim is allowed to travel, in degrees per tick. Lower is smoother and slower. */
    public double blockInRotationSpeed = 28.0D;

    /** Turn the real camera. Off keeps the rotation in the packets only and leaves your view alone. */
    public boolean blockInVisibleRotation = true;

    /** When the wall is finished, walk the camera back to where it started. */
    public boolean blockInReturnRotation = true;

    // ------------------------------------------------------------------ plumbing

    private EagleConfig() {
    }

    public static EagleConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static EagleConfig load() {
        if (Files.exists(FILE)) {
            try {
                EagleConfig parsed = GSON.fromJson(Files.readString(FILE), EagleConfig.class);
                if (parsed != null) {
                    return parsed.sanitise();
                }
            } catch (Exception e) {
                EagleClient.LOGGER.warn("[Eagle] could not read {}, falling back to defaults", FILE, e);
            }
        }
        EagleConfig fresh = new EagleConfig();
        fresh.save();
        return fresh;
    }

    /** Clamp values that would make the module either useless or dangerous. */
    private EagleConfig sanitise() {
        edgeOffset = clamp(edgeOffset, 0.0D, 0.30D);
        releaseMargin = clamp(releaseMargin, 0.0D, 0.30D);
        sneakDelayMs = (int) clamp(sneakDelayMs, 0, 200);
        releaseDelayMs = (int) clamp(releaseDelayMs, 0, 1000);
        safePadCooldownTicks = (int) clamp(safePadCooldownTicks, 0, 40);
        safePadRescueMinFallSpeed = clamp(safePadRescueMinFallSpeed, 0.0D, 2.0D);
        cpsMin = clamp(cpsMin, 1.0D, 20.0D);
        cpsMax = clamp(cpsMax, 1.0D, 20.0D);
        aimMaxAngle = clamp(aimMaxAngle, 1.0D, 180.0D);
        aimDistance = clamp(aimDistance, 1.0D, 32.0D);
        aimHorizontalSpeed = clamp(aimHorizontalSpeed, 0.0D, 1.0D);
        aimVerticalSpeed = clamp(aimVerticalSpeed, 0.0D, 1.0D);
        aimJitterDegrees = clamp(aimJitterDegrees, 0.0D, 5.0D);
        if (aimWhitelist == null) {
            aimWhitelist = new ArrayList<>();
        }
        invChestDelayMs = (int) clamp(invChestDelayMs, INV_CHEST_MIN_DELAY_MS, INV_CHEST_MAX_DELAY_MS);
        invChestMinTier = (int) clamp(invChestMinTier, 1, 5);
        espRange = clamp(espRange, 4.0D, 256.0D);
        espRefreshTicks = (int) clamp(espRefreshTicks, 1, 40);
        blockInLayers = (int) clamp(blockInLayers, 1, 2);
        blockInDelayMs = (int) clamp(blockInDelayMs, 0, 1000);
        blockInRotationSpeed = clamp(blockInRotationSpeed, 1.0D, 180.0D);
        return this;
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    public void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(this));
        } catch (IOException e) {
            EagleClient.LOGGER.warn("[Eagle] could not write {}", FILE, e);
        }
    }
}
