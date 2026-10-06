package com.dmod.eagle;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.dmod.eagle.mixin.EntityFlagsMixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Entity highlighting, visible through terrain.
 *
 * <p><b>The outline</b> is the shared-flags glow bit. {@code Entity#setGlowing} cannot be used for
 * this client side - see {@link EntityFlagsMixin} - so the flag is written directly, which is what the
 * client-side {@code isGlowing()} actually reads. The vanilla renderer then draws the coloured outline
 * through walls, the way a spectral arrow does. Nothing is sent to the server.
 *
 * <p><b>The name</b> is drawn by {@link EspRenderer} rather than by vanilla, because vanilla picks the
 * depth-tested layer whenever the entity is sneaking, and an ESP name that disappears behind a wall is
 * not much of an ESP name. The vanilla label is suppressed for tracked entities from
 * {@code EntityRendererMixin} so the two never stack.
 *
 * <p><b>Performance.</b> Everything expensive - the entity scan, the label text, the flag writes -
 * happens on a timer {@code espRefreshTicks} ticks apart. The per-frame path is one {@code isEmpty}
 * check plus a hash lookup per rendered entity, and one text draw per tracked entity.
 */
public final class EspLogic {

    /** {@code Entity.GLOWING_FLAG_INDEX}: the shared-flags bit {@code isGlowing()} reads client side. */
    private static final int GLOWING_FLAG = 6;

    /** Entities whose glow we turned on, mapped to whatever the flag was beforehand. */
    private static final Map<Entity, Boolean> GLOW_RESTORE = new HashMap<>();

    /** Labels we draw ourselves, keyed by entity. Empty when names are switched off. */
    private static final Map<Entity, Text> LABELS = new HashMap<>();

    private static int ticksUntilRefresh = 0;
    private static ClientWorld lastWorld = null;

    private EspLogic() {
    }

    /** True when at least one entity is currently highlighted. Drives the HUD. */
    public static boolean isActive() {
        return !LABELS.isEmpty() || !GLOW_RESTORE.isEmpty();
    }

    /** How many entities are currently highlighted. */
    public static int trackedCount() {
        return Math.max(LABELS.size(), GLOW_RESTORE.size());
    }

    /** The labels to draw this frame. Read directly by the renderer, never copied. */
    public static Map<Entity, Text> labels() {
        return LABELS;
    }

    /** True when this entity's vanilla name label should be hidden in favour of ours. */
    public static boolean isTracked(Entity entity) {
        return !LABELS.isEmpty() && LABELS.containsKey(entity);
    }

    /** Drop every highlight and put the glow flags back the way we found them. */
    public static void releaseAll() {
        for (Map.Entry<Entity, Boolean> entry : GLOW_RESTORE.entrySet()) {
            setGlowFlag(entry.getKey(), entry.getValue());
        }

        GLOW_RESTORE.clear();
        LABELS.clear();
        ticksUntilRefresh = 0;
    }

    public static void reset() {
        releaseAll();
        lastWorld = null;
    }

    public static void onEndTick(MinecraftClient mc) {
        EagleConfig cfg = EagleConfig.get();

        if (!cfg.espEnabled || mc == null || mc.player == null || mc.world == null) {
            releaseAll();
            lastWorld = null;
            return;
        }

        if (mc.world != lastWorld) {
            // Different world or a fresh connection: nothing we recorded is meaningful any more.
            releaseAll();
            lastWorld = mc.world;
        }

        if (ticksUntilRefresh > 0) {
            ticksUntilRefresh--;
            return;
        }

        ticksUntilRefresh = Math.max(1, cfg.espRefreshTicks);
        refresh(mc, cfg);
    }

    // ------------------------------------------------------------------ the timer body

    private static void refresh(MinecraftClient mc, EagleConfig cfg) {
        ClientPlayerEntity self = mc.player;
        Vec3d eye = self.getEyePos();
        double rangeSq = cfg.espRange * cfg.espRange;

        Box search = self.getBoundingBox().expand(cfg.espRange);
        List<LivingEntity> candidates = mc.world.getEntitiesByClass(
                LivingEntity.class, search, entity -> entity != self && entity.isAlive());

        Set<Entity> tracked = new HashSet<>();
        Map<Entity, Text> newLabels = cfg.espShowNames ? new HashMap<>() : Map.of();

        for (LivingEntity candidate : candidates) {
            boolean isPlayer = candidate instanceof PlayerEntity;

            if (isPlayer ? !cfg.espTargetPlayers : !cfg.espTargetMobs) {
                continue;
            }

            // Squared distance only; no square roots on the hot path.
            if (eye.squaredDistanceTo(candidate.getBoundingBox().getCenter()) > rangeSq) {
                continue;
            }

            tracked.add(candidate);

            if (cfg.espShowNames) {
                newLabels.put(candidate, buildLabel(candidate, eye));
            }
        }

        applyGlow(tracked, cfg);
        LABELS.clear();
        LABELS.putAll(newLabels);
    }

    private static void applyGlow(Set<Entity> tracked, EagleConfig cfg) {
        if (!cfg.espGlow) {
            // The flag was turned off mid-flight: put everything back and stop tracking glow.
            for (Map.Entry<Entity, Boolean> entry : GLOW_RESTORE.entrySet()) {
                setGlowFlag(entry.getKey(), entry.getValue());
            }
            GLOW_RESTORE.clear();
            return;
        }

        // Only touch an entity when it crosses the boundary, never on every refresh.
        for (Entity entity : tracked) {
            if (!GLOW_RESTORE.containsKey(entity)) {
                GLOW_RESTORE.put(entity, getGlowFlag(entity));
                setGlowFlag(entity, true);
            }
        }

        Iterator<Map.Entry<Entity, Boolean>> iterator = GLOW_RESTORE.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<Entity, Boolean> entry = iterator.next();

            if (!tracked.contains(entry.getKey())) {
                // Restore rather than clear, so a spectral arrow or a potion effect survives.
                setGlowFlag(entry.getKey(), entry.getValue());
                iterator.remove();
            }
        }
    }

    private static boolean getGlowFlag(Entity entity) {
        return ((EntityFlagsMixin) entity).eagle$getFlag(GLOWING_FLAG);
    }

    private static void setGlowFlag(Entity entity, boolean value) {
        ((EntityFlagsMixin) entity).eagle$setFlag(GLOWING_FLAG, value);
    }

    private static Text buildLabel(Entity entity, Vec3d eye) {
        MutableText label = Text.empty().append(entity.getDisplayName());

        if (EagleConfig.get().espShowDistance) {
            double distance = Math.sqrt(eye.squaredDistanceTo(entity.getBoundingBox().getCenter()));
            label.append(Text.literal(" " + String.format(Locale.ROOT, "%.1f", distance) + "m")
                    .formatted(Formatting.GRAY));
        }

        return label;
    }
}
