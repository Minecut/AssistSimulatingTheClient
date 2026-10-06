package com.dmod.eagle.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.dmod.eagle.EspLogic;

import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;

/**
 * Hides vanilla's name label for entities the ESP draws itself.
 *
 * <p>{@code getAndUpdateRenderState} is the tidy injection point: it is final, it sits on the base
 * renderer, and it runs after every subclass has filled the state in, so whatever we leave behind is
 * what gets drawn.
 *
 * <p>Clearing {@code nameLabelPos} is enough. Vanilla's own label pass bails out immediately when that
 * field is null, and {@link com.dmod.eagle.EspRenderer} then draws the label instead - always through
 * terrain, which vanilla does not do for a sneaking entity.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @Inject(method = "getAndUpdateRenderState", at = @At("RETURN"))
    private void eagle$espLabel(Entity entity, float tickDelta, CallbackInfoReturnable<EntityRenderState> cir) {
        EntityRenderState state = cir.getReturnValue();

        if (state == null || state.nameLabelPos == null) {
            return;
        }

        if (EspLogic.isTracked(entity)) {
            state.nameLabelPos = null;
        }
    }
}
