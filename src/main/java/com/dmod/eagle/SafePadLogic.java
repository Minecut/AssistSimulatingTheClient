package com.dmod.eagle;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * The safety pad: the part of God Bridge that keeps you alive.
 *
 * <p>Where {@link EagleLogic} stops you at the edge, this module keeps you moving and simply puts a
 * block where your next step is going to land. Two situations are covered:
 *
 * <ul>
 *   <li><b>Predictive</b> — you are on the ground and the same probe Eagle uses says the ground runs
 *       out just in front of you. The block directly ahead, at the level of the one you are standing
 *       on, is filled in before you reach it.</li>
 *   <li><b>Fall rescue</b> — you are already airborne and falling. A block is pushed straight
 *       underneath you.</li>
 * </ul>
 *
 * <p>The placement itself is entirely vanilla: a synthesised {@link BlockHitResult} is fed to
 * {@code ClientPlayerInteractionManager.interactBlock}, which is exactly the call right-clicking a
 * block makes. That means the client prediction, the sequence counter and the packet are all handled
 * by the game's own code.
 *
 * <p>The one thing that is not vanilla is the rotation. God Bridge's core trick, and the reason this
 * works without yanking your camera around, is that the server is told you are aiming at the target
 * face while your client keeps looking wherever you were looking. That is what the two
 * {@link PlayerMoveC2SPacket.LookAndOnGround} packets around the placement do.
 */
public final class SafePadLogic {

    private static final float DEG_TO_RAD = 0.017453292F;

    private static boolean placedThisTick = false;
    private static int cooldown = 0;

    private SafePadLogic() {
    }

    /** True when a block was actually placed during the current client tick. */
    public static boolean isPlacing() {
        return placedThisTick;
    }

    public static void reset() {
        placedThisTick = false;
        cooldown = 0;
    }

    /** Driven from the start of every client tick, before the player moves. */
    public static void onStartTick(MinecraftClient mc) {
        placedThisTick = false;

        if (cooldown > 0) {
            cooldown--;
        }

        EagleConfig cfg = EagleConfig.get();

        if (!cfg.safePadEnabled
                || cooldown > 0
                || mc == null
                || mc.player == null
                || mc.world == null
                || mc.interactionManager == null
                || mc.currentScreen != null) {
            return;
        }

        ClientPlayerEntity player = mc.player;

        if (!player.isAlive() || player.isSpectator()) {
            return;
        }
        if (player.getAbilities().flying || player.isGliding()) {
            return;
        }
        if (player.isSwimming() || player.isClimbing() || player.hasVehicle()) {
            return;
        }

        Hand hand = findBlockHand(player);

        if (hand == null) {
            return;
        }

        BlockPos target;

        if (player.isOnGround()) {
            target = predictiveTarget(mc.world, player, cfg);
        } else if (cfg.safePadFallRescue && player.getVelocity().y < -cfg.safePadRescueMinFallSpeed) {
            target = BlockPos.ofFloored(player.getX(), player.getY() - 1.0D, player.getZ());
        } else {
            return;
        }

        if (target == null || !mc.world.getBlockState(target).isReplaceable()) {
            return;
        }

        BlockHitResult hit = BlockPlacement.findHit(mc.world, target);

        if (hit == null || !BlockPlacement.inReach(player, hit)) {
            return;
        }

        if (place(mc, player, hand, hit)) {
            placedThisTick = true;
            cooldown = Math.max(0, cfg.safePadCooldownTicks);
        }
    }

    // ------------------------------------------------------------------ target selection

    /**
     * The block the player is about to step onto, or {@code null} when nothing needs filling.
     *
     * <p>The gate is the same prediction Eagle uses: only act once the ground under the probe one
     * half-width plus {@code edgeOffset} in front of the player has run out. Filling a gap that is
     * merely visible from a safe distance would turn this into a full auto builder, which is not what
     * a safety net is supposed to be.
     */
    private static BlockPos predictiveTarget(World world, ClientPlayerEntity player, EagleConfig cfg) {
        float forward = player.input.movementForward;
        float sideways = player.input.movementSideways;

        if (forward * forward + sideways * sideways < 1.0E-4F) {
            return null;
        }

        float yawRad = -player.getYaw() * DEG_TO_RAD;
        float cos = MathHelper.cos(yawRad);
        float sin = MathHelper.sin(yawRad);

        double dx = sideways * cos + forward * sin;
        double dz = -sideways * sin + forward * cos;
        double length = Math.sqrt(dx * dx + dz * dz);

        if (length < 1.0E-4D) {
            return null;
        }

        dx /= length;
        dz /= length;

        double lead = 0.3D + Math.max(0.0D, cfg.edgeOffset);

        if (EagleLogic.probe(world, player, dx, dz, lead)) {
            return null;
        }

        // Step one whole block along the dominant axis of travel, so the target is always face
        // adjacent to a block the player is standing on. A diagonal step would have no such neighbour.
        Direction dir = Direction.getFacing(dx, 0.0D, dz);

        if (dir.getAxis().isVertical()) {
            return null;
        }

        BlockPos feet = BlockPos.ofFloored(player.getX(), player.getY() - 0.1D, player.getZ());
        return feet.offset(dir);
    }

    /** The first solid neighbour of {@code target}, described as the face of it that we would click. */
    private static BlockHitResult findHit(World world, BlockPos target) {
        return BlockPlacement.findHit(world, target);
    }

    // ------------------------------------------------------------------ placing

    private static boolean place(MinecraftClient mc, ClientPlayerEntity player, Hand hand, BlockHitResult hit) {
        ClientPlayNetworkHandler network = mc.getNetworkHandler();

        if (network == null) {
            return false;
        }

        float[] rotation = BlockPlacement.rotationTo(player.getEyePos(), hit.getPos());

        if (rotation == null) {
            return false;
        }

        float realYaw = player.getYaw();
        float realPitch = player.getPitch();
        boolean onGround = player.isOnGround();

        // Silent rotation: the server is told the player is aiming at the target face, the client
        // camera never moves. The restore packet is not optional - the client only re-sends its own
        // rotation when it changes, so without it the server would stay stuck looking downwards.
        network.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(rotation[0], rotation[1], onGround, false));

        ActionResult result;

        try {
            result = mc.interactionManager.interactBlock(player, hand, hit);
        } finally {
            network.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(realYaw, realPitch, onGround, false));
        }

        if (result.isAccepted()) {
            player.swingHand(hand);
            return true;
        }

        return false;
    }

    private static Hand findBlockHand(ClientPlayerEntity player) {
        return BlockPlacement.findBlockHand(player);
    }
}
