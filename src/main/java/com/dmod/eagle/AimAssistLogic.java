package com.dmod.eagle;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Aim assist, in the assistive rather than the "lock on" sense.
 *
 * <p>Nothing here writes the player's yaw or pitch. Instead we hand the game a small amount of
 * <em>phantom mouse movement</em>, which the game then turns into camera rotation through exactly the
 * same path as the real thing. The hook is the head of {@code Mouse#updateMouse(double)}: adding to
 * {@code cursorDeltaX}/{@code cursorDeltaY} there is consumed once, in the same expression that
 * consumes the player's own mouse movement, and it inherits vanilla's mouse sensitivity curve for
 * free. That is the in-process equivalent of the "correct the mouse delta before the game sees it"
 * trick, without any of the operating system level input hooking.
 *
 * <p>The camera genuinely moves, which is the point: the assist is meant to look like a player who
 * tracks well, not like input that vanishes.
 *
 * <p>Vanilla's unit chain, for reference:
 *
 * <pre>
 *   degrees = cursorDelta * (sens * 0.6 + 0.2)^3 * 8 * 0.15
 * </pre>
 *
 * <p>Everything in {@link EagleConfig} is expressed in degrees or in plain percentages, and this
 * class does the conversion.
 */
public final class AimAssistLogic {

    /** The exponential approach is normalised to this window, so the speed is framerate independent. */
    private static final double REFERENCE_SECONDS = 0.05D;

    /** The band of the target hitbox that gets aimed at: upper chest, on the head line. */
    private static final double AIM_HEIGHT_FRACTION = 0.82D;

    private static long lastFrameNanos = 0L;
    private static Entity lastTarget = null;
    private static boolean aiming = false;

    private static double jitterX;
    private static double jitterY;
    private static double jitterZ;
    private static double jitterGoalX;
    private static double jitterGoalY;
    private static double jitterGoalZ;
    private static long jitterRerollAt = 0L;

    private AimAssistLogic() {
    }

    /** True while a target is being tracked. Drives the HUD. */
    public static boolean isAiming() {
        return aiming;
    }

    /** Full reset, for when the module is toggled. */
    public static void reset() {
        lastFrameNanos = 0L;
        lastTarget = null;
        aiming = false;
        jitterX = 0.0D;
        jitterY = 0.0D;
        jitterZ = 0.0D;
        jitterGoalX = 0.0D;
        jitterGoalY = 0.0D;
        jitterGoalZ = 0.0D;
        jitterRerollAt = 0L;
    }

    /** Drop the frame timing and the tracked target, but keep the jitter smooth across the gap. */
    private static void dropTarget() {
        lastFrameNanos = 0L;
        lastTarget = null;
        aiming = false;
    }

    /**
     * Works out this frame's contribution to the mouse movement.
     *
     * @return {@code {cursorDeltaX, cursorDeltaY}} to add, or {@code null} when nothing should happen
     */
    public static double[] mouseCorrection(MinecraftClient mc) {
        aiming = false;

        EagleConfig cfg = EagleConfig.get();

        if (!cfg.aimEnabled
                || mc == null
                || mc.player == null
                || mc.world == null
                || mc.options == null
                || mc.currentScreen != null) {
            dropTarget();
            return null;
        }

        ClientPlayerEntity self = mc.player;

        if (!self.isAlive() || self.isSpectator()) {
            dropTarget();
            return null;
        }

        // "Click Aim": only while the player is actually holding the attack button.
        if (cfg.aimClickOnly && !mc.options.attackKey.isPressed()) {
            dropTarget();
            return null;
        }

        if (cfg.aimWeaponOnly && !isWeapon(self.getMainHandStack())) {
            dropTarget();
            return null;
        }

        long now = System.nanoTime();
        double dt = lastFrameNanos == 0L ? 1.0D / 60.0D : (now - lastFrameNanos) / 1.0E9D;
        lastFrameNanos = now;
        dt = Math.max(1.0E-4D, Math.min(0.1D, dt));

        Entity target = findTarget(mc, self, cfg);

        if (target == null) {
            lastTarget = null;
            return null;
        }

        if (target != lastTarget) {
            // Fresh target: snap the wobble onto it rather than sliding in from wherever it was.
            lastTarget = target;
            rerollJitter(now);
            jitterX = jitterGoalX;
            jitterY = jitterGoalY;
            jitterZ = jitterGoalZ;
        } else if (now >= jitterRerollAt) {
            rerollJitter(now);
        }

        // Drift towards the goal instead of jumping, so the re-roll never reads as a twitch.
        double drift = Math.min(1.0D, dt * 9.0D);
        jitterX += (jitterGoalX - jitterX) * drift;
        jitterY += (jitterGoalY - jitterY) * drift;
        jitterZ += (jitterGoalZ - jitterZ) * drift;

        Vec3d eye = self.getEyePos();
        Vec3d base = aimPoint(target);
        double radius = Math.tan(Math.toRadians(Math.max(0.0D, cfg.aimJitterDegrees)))
                * Math.max(1.0D, eye.distanceTo(base));
        Vec3d aim = base.add(jitterX * radius, jitterY * radius, jitterZ * radius);

        double dx = aim.x - eye.x;
        double dy = aim.y - eye.y;
        double dz = aim.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);

        float wantYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float wantPitch = (float) -Math.toDegrees(Math.atan2(dy, flat));

        double errorYaw = MathHelper.wrapDegrees(wantYaw - self.getYaw());
        double errorPitch = wantPitch - self.getPitch();

        double stepYaw = errorYaw * approach(cfg.aimHorizontalSpeed, dt);
        double stepPitch = errorPitch * approach(cfg.aimVerticalSpeed, dt);

        if (cfg.aimStrafeIncrease) {
            // When the player is moving, the crosshair has to work harder to stay on target, and so
            // does the assist. Walk speed is about 0.216 blocks per tick.
            double boost = 1.0D + Math.min(0.5D, self.getVelocity().horizontalLength() * 1.6D);
            stepYaw *= boost;
            stepPitch *= boost;
        }

        aiming = true;

        if (Math.abs(stepYaw) < 1.0E-4D && Math.abs(stepPitch) < 1.0E-4D) {
            return null;
        }

        double sensitivity = mc.options.getMouseSensitivity().getValue();
        double f = sensitivity * 0.6D + 0.2D;
        double h = f * f * f * 8.0D;

        // Sync mode adds raw cursor units, so vanilla's own curve scales the result by h - a stronger
        // mouse setting gets a stronger pull, which is the point. Otherwise the degrees are exact.
        double divisor = 0.15D * (cfg.aimSensitivitySync ? 1.0D : h);
        double invert = mc.options.getInvertYMouse().getValue() ? -1.0D : 1.0D;

        return new double[] { stepYaw / divisor, stepPitch / (divisor * invert) };
    }

    // ------------------------------------------------------------------ target selection

    private static Entity findTarget(MinecraftClient mc, ClientPlayerEntity self, EagleConfig cfg) {
        Box search = self.getBoundingBox().expand(cfg.aimDistance);
        List<LivingEntity> candidates = mc.world.getEntitiesByClass(
                LivingEntity.class, search, entity -> entity != self && entity.isAlive());

        Vec3d eye = self.getEyePos();
        Vec3d look = self.getRotationVec(1.0F);
        double cosLimit = Math.cos(Math.toRadians(Math.max(0.0D, Math.min(180.0D, cfg.aimMaxAngle))));

        Entity best = null;
        double bestAngle = Double.MAX_VALUE;

        for (LivingEntity candidate : candidates) {
            if (candidate.isRemoved()) {
                continue;
            }

            boolean isPlayer = candidate instanceof PlayerEntity;

            if (isPlayer ? !cfg.aimTargetPlayers : !cfg.aimTargetMobs) {
                continue;
            }
            if (isPlayer && ((PlayerEntity) candidate).isSpectator()) {
                continue;
            }
            if (isWhitelisted(cfg, candidate)) {
                continue;
            }

            Vec3d centre = aimPoint(candidate);

            if (eye.squaredDistanceTo(centre) > cfg.aimDistance * cfg.aimDistance) {
                continue;
            }

            Vec3d toTarget = centre.subtract(eye);
            double length = toTarget.length();

            if (length < 1.0E-4D) {
                continue;
            }

            double cos = look.dotProduct(toTarget) / length;

            if (cos < cosLimit) {
                continue;
            }

            if (!cfg.aimThroughWalls && !self.canSee(candidate)) {
                continue;
            }

            // Closest to the crosshair wins, which is what keeps the pull feeling like a magnet
            // rather than a yank towards whatever happens to be nearest.
            double angle = Math.toDegrees(Math.acos(Math.max(-1.0D, Math.min(1.0D, cos))));

            if (angle < bestAngle) {
                bestAngle = angle;
                best = candidate;
            }
        }

        return best;
    }

    private static Vec3d aimPoint(Entity entity) {
        Box box = entity.getBoundingBox();
        return new Vec3d(
                (box.minX + box.maxX) * 0.5D,
                box.minY + (box.maxY - box.minY) * AIM_HEIGHT_FRACTION,
                (box.minZ + box.maxZ) * 0.5D);
    }

    private static boolean isWhitelisted(EagleConfig cfg, Entity entity) {
        if (cfg.aimWhitelist == null || cfg.aimWhitelist.isEmpty()) {
            return false;
        }

        String name = entity instanceof PlayerEntity
                ? ((PlayerEntity) entity).getGameProfile().getName()
                : entity.getName().getString();

        for (String entry : cfg.aimWhitelist) {
            if (entry != null && entry.equalsIgnoreCase(name)) {
                return true;
            }
        }

        return false;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Fraction of the remaining error to close this frame.
     *
     * <p>{@code speed} is the fraction closed over one 50 ms reference window, and the exponent makes
     * the result independent of the frame rate: a player at 240 fps and one at 60 fps see the same
     * tracking speed.
     */
    private static double approach(double speed, double dt) {
        double s = Math.max(0.0D, Math.min(1.0D, speed));
        return 1.0D - Math.pow(1.0D - s, dt / REFERENCE_SECONDS);
    }

    private static void rerollJitter(long now) {
        jitterRerollAt = now + 150_000_000L
                + (long) (ThreadLocalRandom.current().nextDouble() * 250_000_000.0D);
        jitterGoalX = ThreadLocalRandom.current().nextDouble() - 0.5D;
        jitterGoalY = ThreadLocalRandom.current().nextDouble() - 0.5D;
        jitterGoalZ = ThreadLocalRandom.current().nextDouble() - 0.5D;
    }

    private static boolean isWeapon(ItemStack stack) {
        return !stack.isEmpty()
                && (stack.getItem() instanceof SwordItem || stack.getItem() instanceof AxeItem);
    }
}
