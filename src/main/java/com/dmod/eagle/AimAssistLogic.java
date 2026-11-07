package com.dmod.eagle;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.DyedColorComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SwordItem;
import net.minecraft.util.Hand;
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

    /** Slots the team check compares, in a fixed order so two players line up slot for slot. */
    private static final EquipmentSlot[] ARMOUR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /**
     * How far off the crosshair the target may sit before the auto attack swings.
     *
     * <p>Deliberately tighter than the assist's own cone: hitting something 40 degrees off centre
     * would send a swing at empty air as far as the server is concerned, so the assist gets to finish
     * converging first.
     */
    private static final double ATTACK_CONE_COS = Math.cos(Math.toRadians(8.0D));

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

    // ------------------------------------------------------------------ auto attack

    /**
     * Swings at the target the assist is tracking.
     *
     * <p>Runs on the client tick rather than per frame, because everything it depends on - reach and
     * the attack cooldown - advances in ticks. Four conditions all have to hold before a swing is
     * allowed through: the crosshair has actually converged on the target, it is visible, it is inside
     * the interaction range, and the attack is fully charged. Charging to full is what keeps the rate
     * at vanilla speed instead of something a server would see as impossible.
     */
    public static void onEndTick(MinecraftClient mc) {
        EagleConfig cfg = EagleConfig.get();

        if (!cfg.aimEnabled || !cfg.aimAutoAttack) {
            return;
        }

        if (mc == null
                || mc.player == null
                || mc.world == null
                || mc.interactionManager == null
                || mc.currentScreen != null) {
            return;
        }

        ClientPlayerEntity self = mc.player;

        if (!self.isAlive() || self.isSpectator()) {
            return;
        }

        // lastTarget is refreshed every frame by mouseCorrection; a stale one is filtered out below.
        Entity target = lastTarget;

        if (target == null
                || target.isRemoved()
                || !target.isAlive()
                || target.getWorld() != mc.world) {
            return;
        }

        if (!cfg.aimThroughWalls && !self.canSee(target)) {
            return;
        }

        Vec3d eye = self.getEyePos();
        Vec3d toTarget = aimPoint(target).subtract(eye);
        double distance = toTarget.length();

        if (distance < 1.0E-4D) {
            return;
        }

        // Vanilla measures reach to the closest point of the hitbox, so half the entity's width on
        // each horizontal axis is the right allowance.
        double reach = self.getEntityInteractionRange() + target.getWidth() * 0.5D;

        if (distance > reach) {
            return;
        }

        double cos = self.getRotationVec(1.0F).dotProduct(toTarget) / distance;

        if (cos < ATTACK_CONE_COS) {
            return;
        }

        if (self.getAttackCooldownProgress(0.5F) < 1.0F) {
            return;
        }

        mc.interactionManager.attackEntity(self, target);
        self.swingHand(Hand.MAIN_HAND);
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
            // Team check: identical dyed armour means a team mate, so leave them out of the pool.
            if (cfg.aimTeamCheck && isTeamMate(self, candidate)) {
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

    /**
     * True when the other player is wearing the same dyed armour as us.
     *
     * <p>Team servers mark sides by dyeing leather armour, so matching dye is the cheapest reliable
     * signal available on the client. The rule is strict on purpose: every slot where <em>we</em> have a
     * colour must match theirs exactly, and at least one such slot has to exist. A player in the right
     * helmet but the wrong chest is not a team mate, and with no dyed armour on us at all there is
     * nothing to compare, so nobody gets excluded.
     *
     * <p>Undyed armour carries no {@code DYED_COLOR} component, so it reads as -1 - which correctly
     * fails to match any real colour.
     */
    private static boolean isTeamMate(LivingEntity self, LivingEntity other) {
        return sameColours(armourColours(self), armourColours(other));
    }

    /** The four dye colours of an entity's armour, in {@link #ARMOUR_SLOTS} order, -1 for undyed. */
    private static int[] armourColours(LivingEntity entity) {
        int[] colours = new int[ARMOUR_SLOTS.length];

        for (int i = 0; i < ARMOUR_SLOTS.length; i++) {
            colours[i] = dyedColor(entity.getEquippedStack(ARMOUR_SLOTS[i]));
        }

        return colours;
    }

    /**
     * The comparison itself, kept separate from the entity plumbing so it can be exercised directly.
     *
     * <p>Strict on purpose: every slot where we have a colour must match theirs exactly, and at least
     * one such slot has to exist. A player in the right helmet but the wrong chest is not a team mate,
     * and with no dyed armour on us there is nothing to compare, so nobody is excluded.
     */
    static boolean sameColours(int[] mine, int[] theirs) {
        int matched = 0;

        for (int i = 0; i < mine.length; i++) {
            if (mine[i] < 0) {
                continue;
            }

            if (i >= theirs.length || mine[i] != theirs[i]) {
                return false;
            }

            matched++;
        }

        return matched > 0;
    }

    private static int dyedColor(ItemStack stack) {
        if (stack.isEmpty()) {
            return -1;
        }

        DyedColorComponent dyed = stack.get(DataComponentTypes.DYED_COLOR);
        return dyed == null ? -1 : dyed.rgb();
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
