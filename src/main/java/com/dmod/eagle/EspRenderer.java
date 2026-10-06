package com.dmod.eagle;

import java.util.Map;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/**
 * Draws the ESP name labels.
 *
 * <p>This exists instead of letting vanilla draw the label because vanilla picks its text layer from
 * {@code state.sneaking}: the layer is {@code SEE_THROUGH} normally but falls back to the depth-tested
 * one while the entity is sneaking, so a crouching player's name hides behind terrain. Here the layer
 * is always {@code SEE_THROUGH}, which is the whole point of the module.
 *
 * <p>The world matrix stack handed to {@code AFTER_ENTITIES} is already rotated to the camera, so
 * positions are translated by {@code worldPos - cameraPos}, exactly as vanilla's own entity and label
 * rendering does it.
 */
public final class EspRenderer {

    /** Vanilla's label scale: 0.025 blocks per text pixel, flipped on Y so text reads upright. */
    private static final float LABEL_SCALE = 0.025F;

    /** Full-bright lightmap coordinate, so labels stay readable in the dark. */
    private static final int FULL_BRIGHT = 0xF000F0;

    /** Vanilla's translucent label backdrop. */
    private static final int LABEL_BACKGROUND = 0x40000000;

    private EspRenderer() {
    }

    public static void render(WorldRenderContext context) {
        Map<Entity, Text> labels = EspLogic.labels();

        if (labels.isEmpty()) {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc.textRenderer == null || mc.player == null) {
            return;
        }

        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider consumers = context.consumers();

        if (matrices == null || consumers == null) {
            return;
        }

        Camera camera = context.camera();
        Vec3d cameraPos = camera.getPos();
        float tickDelta = context.tickCounter().getTickDelta(false);
        TextRenderer textRenderer = mc.textRenderer;

        for (Map.Entry<Entity, Text> entry : labels.entrySet()) {
            Entity entity = entry.getKey();
            Vec3d pos = entity.getLerpedPos(tickDelta);
            Text text = entry.getValue();

            matrices.push();
            matrices.translate(
                    pos.x - cameraPos.x,
                    pos.y + entity.getHeight() + 0.5D - cameraPos.y,
                    pos.z - cameraPos.z);
            matrices.multiply(camera.getRotation());
            matrices.scale(LABEL_SCALE, -LABEL_SCALE, LABEL_SCALE);

            Matrix4f matrix = matrices.peek().getPositionMatrix();

            textRenderer.draw(
                    text,
                    -textRenderer.getWidth(text) / 2.0F,
                    0.0F,
                    0xFFFFFFFF,
                    true,
                    matrix,
                    consumers,
                    TextRenderer.TextLayerType.SEE_THROUGH,
                    LABEL_BACKGROUND,
                    FULL_BRIGHT);

            matrices.pop();
        }
    }
}
