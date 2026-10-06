package com.dmod.eagle;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * The placement primitives shared by every module that puts blocks down for you.
 *
 * <p>None of this forges packets. Moving a block out of the inventory into the world is a thing the
 * game already knows how to do: a synthesised {@link BlockHitResult} handed to
 * {@code ClientPlayerInteractionManager.interactBlock} is exactly the call right-clicking a block
 * makes, so the client prediction, the sequence counter and the packet all stay vanilla's business.
 */
public final class BlockPlacement {

    /**
     * Faces are tried in this order so the most natural support wins first: the block below the
     * target (clicking its top), then the four horizontal neighbours, and only then the block above
     * (clicking its underside, for a target that has nothing but a ceiling to lean on).
     */
    public static final Direction[] FACE_ORDER = {
            Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.DOWN
    };

    private BlockPlacement() {
    }

    // ------------------------------------------------------------------ hands

    public static Hand findBlockHand(ClientPlayerEntity player) {
        if (isBlock(player.getMainHandStack())) {
            return Hand.MAIN_HAND;
        }
        if (isBlock(player.getOffHandStack())) {
            return Hand.OFF_HAND;
        }
        return null;
    }

    public static boolean isBlock(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem;
    }

    // ------------------------------------------------------------------ geometry

    /** The first solid neighbour of {@code target}, described as the face of it we would click. */
    public static BlockHitResult findHit(World world, BlockPos target) {
        for (Direction face : FACE_ORDER) {
            // Clicking block N on face F places the new block at N.offset(F), so N is the neighbour
            // on the opposite side of the target.
            BlockPos neighbour = target.offset(face.getOpposite());
            BlockState state = world.getBlockState(neighbour);

            if (state.isAir() || state.isReplaceable()) {
                continue;
            }

            Vec3d hit = new Vec3d(
                    neighbour.getX() + 0.5D + face.getOffsetX() * 0.5D,
                    neighbour.getY() + 0.5D + face.getOffsetY() * 0.5D,
                    neighbour.getZ() + 0.5D + face.getOffsetZ() * 0.5D);

            return new BlockHitResult(hit, face, neighbour, false);
        }

        return null;
    }

    /** False when the server would throw the placement away as out of range. */
    public static boolean inReach(ClientPlayerEntity player, BlockHitResult hit) {
        double range = player.getBlockInteractionRange();
        return player.getEyePos().squaredDistanceTo(hit.getPos()) <= range * range;
    }

    /**
     * True when the block would be placed inside the player.
     *
     * <p>Vanilla refuses those anyway - {@code BlockItem.canPlace} runs the block through
     * {@code World.canPlace} with the player's own shape context - so checking first just avoids
     * burning a rotation and a packet on a placement that cannot happen.
     */
    public static boolean clippedByPlayer(ClientPlayerEntity player, BlockPos target) {
        return player.getBoundingBox().intersects(new Box(target));
    }

    /** Vanilla yaw convention: 0 faces +Z, and the look vector is {@code (-sin yaw, 0, cos yaw)}. */
    public static float[] rotationTo(Vec3d eye, Vec3d target) {
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double flat = Math.sqrt(dx * dx + dz * dz);

        if (flat < 1.0E-6D && Math.abs(dy) < 1.0E-6D) {
            return null;
        }

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, flat));
        return new float[] { yaw, pitch };
    }

    // ------------------------------------------------------------------ placing

    /** The right-click. Caller is responsible for whatever rotation it wants the server to see. */
    public static boolean interact(MinecraftClient mc, ClientPlayerEntity player, Hand hand, BlockHitResult hit) {
        if (mc.interactionManager == null || player.getItemCooldownManager().isCoolingDown(player.getStackInHand(hand))) {
            return false;
        }

        ActionResult result = mc.interactionManager.interactBlock(player, hand, hit);

        if (result.isAccepted()) {
            player.swingHand(hand);
            return true;
        }

        return false;
    }
}
