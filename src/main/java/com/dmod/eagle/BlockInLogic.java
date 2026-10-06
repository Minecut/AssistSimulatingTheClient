package com.dmod.eagle;

import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

/**
 * Walls the player in.
 *
 * <p>While enabled it keeps the four blocks beside the player filled, optionally a second layer at
 * head height and optionally the corners too. Placement goes through the same
 * {@link BlockPlacement} primitives SafePad uses, so a block is placed by synthesising the hit result
 * a right-click would produce and handing it to {@code interactBlock}.
 *
 * <p><b>The rotation is the interesting part.</b> Reaching the face of a block beside you means aiming
 * well away from where you are looking, and doing that in one tick is exactly the kind of perfectly
 * instantaneous snap that gets noticed. So the aim is carried in {@code aimYaw}/{@code aimPitch} and
 * walked toward the target at a bounded number of degrees per tick, and walked back again once there
 * is nothing left to place.
 *
 * <p>Two ways to spend that rotation:
 *
 * <ul>
 *   <li><b>Visible</b> (default) - the camera really turns. Nothing conflicts, because vanilla's own
 *       rotation packets are then carrying the same value we are.</li>
 *   <li><b>Silent</b> - the rotation only ever exists in {@code PlayerMoveC2SPacket.LookAndOnGround},
 *       and the camera stays on whatever you were looking at. Cheaper on the eyes, but vanilla will
 *       still send your real rotation whenever you move the mouse, so the two interleave.</li>
 * </ul>
 */
public final class BlockInLogic {

    /** Cardinals first, then corners, so a wall goes up before its diagonals. */
    private static final Direction[] SIDES = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    private static final int[][] CORNERS = { { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 } };

    private static float aimYaw;
    private static float aimPitch;
    private static float restYaw;
    private static float restPitch;
    private static boolean engaged;
    private static boolean placedThisTick;
    private static long nextPlaceAt;

    private BlockInLogic() {
    }

    /** True while a block was placed this tick or the aim is still being moved. Drives the HUD. */
    public static boolean isWorking() {
        return placedThisTick || engaged;
    }

    /** Clears the state flags. The next tick's {@link #onEndTick} finishes the rotation cleanup. */
    public static void reset() {
        placedThisTick = false;
        nextPlaceAt = 0L;
    }

    public static void onEndTick(MinecraftClient mc) {
        placedThisTick = false;

        EagleConfig cfg = EagleConfig.get();

        if (!cfg.blockInEnabled
                || mc == null
                || mc.player == null
                || mc.world == null
                || mc.interactionManager == null
                || mc.currentScreen != null) {
            release(mc);
            return;
        }

        ClientPlayerEntity player = mc.player;

        if (!player.isAlive() || player.isSpectator() || player.getAbilities().flying
                || player.isGliding() || player.isSwimming() || player.hasVehicle()) {
            release(mc);
            return;
        }

        Hand hand = BlockPlacement.findBlockHand(player);
        BlockPos target = hand == null ? null : nextTarget(mc, player, cfg);

        if (target == null) {
            walkHome(mc, player, cfg);
            return;
        }

        BlockHitResult hit = BlockPlacement.findHit(mc.world, target);

        if (hit == null || !BlockPlacement.inReach(player, hit)) {
            return;
        }

        float[] want = BlockPlacement.rotationTo(player.getEyePos(), hit.getPos());

        if (want == null) {
            return;
        }

        if (!engaged) {
            engaged = true;
            restYaw = player.getYaw();
            restPitch = player.getPitch();
            aimYaw = restYaw;
            aimPitch = restPitch;
        }

        boolean arrived = stepTowards(want[0], want[1], speed(cfg));
        applyAim(mc, player, cfg);

        long now = System.currentTimeMillis();

        if (arrived && now >= nextPlaceAt) {
            if (BlockPlacement.interact(mc, player, hand, hit)) {
                placedThisTick = true;
                nextPlaceAt = now + Math.max(0, cfg.blockInDelayMs);
            } else {
                // Blocked, out of blocks, or rejected: back off briefly instead of hammering it.
                nextPlaceAt = now + 250L;
            }
        }
    }

    // ------------------------------------------------------------------ targets

    /**
     * The first block that still needs filling, or {@code null} when the player is already walled in.
     *
     * <p>Layers are walked bottom-up: the head layer's support is the feet-level block below it, so
     * filling the feet row first is what makes the second row placeable at all.
     */
    private static BlockPos nextTarget(MinecraftClient mc, ClientPlayerEntity player, EagleConfig cfg) {
        BlockPos feet = BlockPos.ofFloored(player.getX(), player.getY(), player.getZ());
        int layers = Math.max(1, Math.min(2, cfg.blockInLayers));

        for (int layer = 0; layer < layers; layer++) {
            BlockPos base = feet.up(layer);

            for (Direction side : SIDES) {
                if (isFillable(mc, player, base.offset(side))) {
                    return base.offset(side);
                }
            }

            if (!cfg.blockInCorners) {
                continue;
            }

            for (int[] corner : CORNERS) {
                BlockPos candidate = base.add(corner[0], 0, corner[1]);

                if (isFillable(mc, player, candidate)) {
                    return candidate;
                }
            }
        }

        return null;
    }

    private static boolean isFillable(MinecraftClient mc, ClientPlayerEntity player, BlockPos target) {
        World world = mc.world;

        if (!world.getBlockState(target).isReplaceable()) {
            return false;
        }

        if (BlockPlacement.clippedByPlayer(player, target)) {
            return false;
        }

        BlockHitResult hit = BlockPlacement.findHit(world, target);
        return hit != null && BlockPlacement.inReach(player, hit);
    }

    // ------------------------------------------------------------------ rotation

    private static float speed(EagleConfig cfg) {
        return (float) Math.max(1.0D, Math.min(180.0D, cfg.blockInRotationSpeed));
    }

    /**
     * Steps the aim toward a target by at most {@code maxStep} degrees this tick.
     *
     * @return true once the aim has reached it
     */
    private static boolean stepTowards(float wantYaw, float wantPitch, float maxStep) {
        double deltaYaw = MathHelper.wrapDegrees(wantYaw - aimYaw);
        double deltaPitch = wantPitch - aimPitch;
        double distance = Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);

        if (distance <= maxStep || distance < 1.0E-4D) {
            aimYaw = wantYaw;
            aimPitch = wantPitch;
            return true;
        }

        aimYaw += (float) (deltaYaw / distance * maxStep);
        aimPitch += (float) (deltaPitch / distance * maxStep);
        return false;
    }

    private static void applyAim(MinecraftClient mc, ClientPlayerEntity player, EagleConfig cfg) {
        float pitch = Math.max(-90.0F, Math.min(90.0F, aimPitch));

        if (cfg.blockInVisibleRotation) {
            player.setYaw(aimYaw);
            player.setPitch(pitch);
            return;
        }

        ClientPlayNetworkHandler network = mc.getNetworkHandler();

        if (network != null) {
            network.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                    aimYaw, pitch, player.isOnGround(), false));
        }
    }

    /** Nothing left to place: walk the aim back and then let go of it. */
    private static void walkHome(MinecraftClient mc, ClientPlayerEntity player, EagleConfig cfg) {
        if (!engaged) {
            return;
        }

        boolean holdRest = cfg.blockInVisibleRotation && cfg.blockInReturnRotation;
        float homeYaw = holdRest ? restYaw : player.getYaw();
        float homePitch = holdRest ? restPitch : player.getPitch();

        boolean arrived = stepTowards(homeYaw, homePitch, speed(cfg));
        applyAim(mc, player, cfg);

        if (arrived) {
            release(mc);
        }
    }

    /** Hands the rotation back to the player. */
    private static void release(MinecraftClient mc) {
        if (!engaged) {
            return;
        }

        engaged = false;

        EagleConfig cfg = EagleConfig.get();

        if (cfg.blockInVisibleRotation || mc == null || mc.player == null) {
            return;
        }

        ClientPlayNetworkHandler network = mc.getNetworkHandler();

        if (network != null) {
            ClientPlayerEntity player = mc.player;
            network.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                    player.getYaw(), player.getPitch(), player.isOnGround(), false));
        }
    }
}
