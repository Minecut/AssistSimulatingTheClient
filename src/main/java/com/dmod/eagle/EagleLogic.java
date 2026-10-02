package com.dmod.eagle;

import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * The actual Eagle / "legit scaffold" brain.
 *
 * <p>The idea, exactly as in the description this was built from: the player is about to walk off
 * the edge of a block, so hold the vanilla sneak key for them. Sneaking is what makes this look
 * human, because vanilla already refuses to let a crouching player step into thin air; all this
 * class does is decide <em>when</em> the crouch happens.
 *
 * <p>That decision is a single prediction: take the direction the player is currently trying to
 * move in, place a small probe box one player-half-width plus {@code edgeOffset} ahead of their
 * centre, and ask the world whether anything solid is under that probe. Nothing solid means the
 * front of the hitbox is about to leave the block, so sneak.
 *
 * <p>The class is called once per client tick from {@link com.dmod.eagle.mixin.KeyboardInputMixin},
 * right after vanilla sampled the keyboard, so the fake crouch is visible to the same movement code
 * that vanilla uses.
 */
public final class EagleLogic {

    /** Half of the vanilla player hitbox width (0.6 / 2). */
    private static final double HALF_WIDTH = 0.3D;

    /** Horizontal half size of the ground probe. Small, so it reads as a point sample. */
    private static final double PROBE_RADIUS = 0.06D;

    /** How far below the feet a supporting block is searched for. */
    private static final double PROBE_DEPTH = 0.12D;

    private static final float DEG_TO_RAD = 0.017453292F;

    /**
     * The real sneak key state, refreshed by the mixin before {@link #shouldSneak()} runs.
     * Used so that a player who is genuinely holding shift is never fought with.
     */
    public static boolean physicalSneak = false;

    private static boolean forcing = false;
    private static boolean engaged = false;

    private static long edgeSince = -1L;
    private static long clearSince = -1L;
    private static long waitMs = 0L;

    private static double lastDirX = 0.0D;
    private static double lastDirZ = 0.0D;

    private EagleLogic() {
    }

    /** True when the sneak currently in effect is ours rather than the player's own key. */
    public static boolean isForcing() {
        return forcing;
    }

    /** True while the module considers the player to be on an edge. Drives the HUD readout. */
    public static boolean isEngaged() {
        return engaged;
    }

    /** Forget every timing/state machine value. Called when the module is toggled or leaves the world. */
    public static void reset() {
        forcing = false;
        engaged = false;
        edgeSince = -1L;
        clearSince = -1L;
        waitMs = 0L;
    }

    /**
     * Decides for this tick.
     *
     * @return {@code true} if the sneak key should read as pressed
     */
    public static boolean shouldSneak() {
        forcing = false;

        EagleConfig cfg = EagleConfig.get();
        MinecraftClient mc = MinecraftClient.getInstance();

        if (!cfg.enabled
                || mc == null
                || mc.world == null
                || mc.player == null
                || mc.options == null
                || mc.currentScreen != null) {
            reset();
            return false;
        }

        ClientPlayerEntity player = mc.player;

        // The safety pad takes priority: when it just managed to lay a block down, the player is meant
        // to keep walking, not to be stopped at the edge. Eagle stays armed as its fallback.
        if (SafePadLogic.isPlacing()) {
            reset();
            return false;
        }

        if (!isEligible(player, cfg)) {
            reset();
            return false;
        }

        if (!resolveDirection(cfg, player)) {
            reset();
            return false;
        }

        // Trigger on the far probe, release on the near one: that is the hysteresis that keeps the
        // crouch stable while the player creeps along a seam instead of flickering every tick.
        double farLead = HALF_WIDTH + Math.max(0.0D, cfg.edgeOffset);
        double nearLead = Math.max(0.02D, farLead - Math.max(0.0D, cfg.releaseMargin));

        boolean farSupported = probe(mc.world, player, lastDirX, lastDirZ, farLead);
        boolean nearSupported = probe(mc.world, player, lastDirX, lastDirZ, nearLead);

        long now = System.currentTimeMillis();

        if (!engaged) {
            if (farSupported) {
                edgeSince = -1L;
                return false;
            }

            if (edgeSince < 0L) {
                edgeSince = now;
                waitMs = pickDelay(cfg.sneakDelayMs, cfg.randomizeDelay);
            }

            if (now - edgeSince < waitMs) {
                return false;
            }

            engaged = true;
            clearSince = -1L;
        } else {
            if (nearSupported) {
                if (clearSince < 0L) {
                    clearSince = now;
                    waitMs = pickDelay(cfg.releaseDelayMs, cfg.randomizeDelay);
                }

                if (now - clearSince >= waitMs) {
                    reset();
                    return false;
                }
            } else {
                clearSince = -1L;
            }
        }

        forcing = true;
        return true;
    }

    // ------------------------------------------------------------------ internals

    /**
     * Everything that should make the module back off entirely. Sneak does not stop a fall once the
     * player is already airborne, and the vanilla ledge protection only applies to plain walking.
     */
    private static boolean isEligible(ClientPlayerEntity player, EagleConfig cfg) {
        if (physicalSneak) {
            return false;
        }
        if (!player.isAlive() || player.isSpectator()) {
            return false;
        }
        if (player.getAbilities().flying || player.isGliding()) {
            return false;
        }
        if (player.isSwimming() || player.isClimbing() || player.isTouchingWater()) {
            return false;
        }
        if (player.hasVehicle()) {
            return false;
        }
        if (!player.isOnGround()) {
            return false;
        }
        if (cfg.onlyWhileBridging && !isHoldingBlock(player)) {
            return false;
        }
        return true;
    }

    private static boolean isHoldingBlock(ClientPlayerEntity player) {
        return player.getMainHandStack().getItem() instanceof BlockItem
                || player.getOffHandStack().getItem() instanceof BlockItem;
    }

    /**
     * Works out the unit vector the player is trying to walk along, in world space, and caches it in
     * {@link #lastDirX}/{@link #lastDirZ}.
     *
     * <p>Keyboard input is preferred because it reacts on the same tick, velocity is the fallback for
     * being pushed around, and the last known direction keeps the crouch held once the player stops
     * at the edge to place a block.
     *
     * @return {@code false} when there is nothing sensible to predict from
     */
    private static boolean resolveDirection(EagleConfig cfg, ClientPlayerEntity player) {
        float forward = player.input.movementForward;
        float sideways = player.input.movementSideways;

        double dx;
        double dz;

        if (forward * forward + sideways * sideways > 1.0E-4F) {
            // Vanilla builds this vector as (sideways, 0, forward) rotated by -yaw. Yaw 0 faces +Z.
            float yawRad = -player.getYaw() * DEG_TO_RAD;
            float cos = MathHelper.cos(yawRad);
            float sin = MathHelper.sin(yawRad);

            dx = sideways * cos + forward * sin;
            dz = -sideways * sin + forward * cos;
        } else if (!cfg.keepSneakWhileIdle) {
            return false;
        } else {
            Vec3d velocity = player.getVelocity();

            if (velocity.horizontalLengthSquared() > 1.0E-4D) {
                dx = velocity.x;
                dz = velocity.z;
            } else if (engaged && (lastDirX != 0.0D || lastDirZ != 0.0D)) {
                dx = lastDirX;
                dz = lastDirZ;
            } else {
                return false;
            }
        }

        double length = Math.sqrt(dx * dx + dz * dz);

        if (length < 1.0E-4D) {
            return false;
        }

        lastDirX = dx / length;
        lastDirZ = dz / length;
        return true;
    }

    /**
     * Places the probe {@code lead} blocks ahead of the player centre, along {@code (dirX, dirZ)},
     * and reports whether the world has any collision under it at foot level.
     *
     * <p>Shared with the safety pad, which uses the very same question to decide that the player is
     * about to step into thin air.
     */
    static boolean probe(World world, ClientPlayerEntity player, double dirX, double dirZ, double lead) {
        double x = player.getX() + dirX * lead;
        double z = player.getZ() + dirZ * lead;
        double feet = player.getY();

        // Feet sit exactly on y, so dipping the probe a little below y lands it inside the block top
        // face and, importantly, misses entirely when that block is not there.
        Box probe = new Box(
                x - PROBE_RADIUS, feet - PROBE_DEPTH, z - PROBE_RADIUS,
                x + PROBE_RADIUS, feet + 0.001D, z + PROBE_RADIUS);

        return !world.isSpaceEmpty(player, probe);
    }

    private static long pickDelay(int base, boolean randomize) {
        if (base <= 0) {
            return 0L;
        }
        if (!randomize) {
            return base;
        }
        return ThreadLocalRandom.current().nextInt(base + 1);
    }
}
