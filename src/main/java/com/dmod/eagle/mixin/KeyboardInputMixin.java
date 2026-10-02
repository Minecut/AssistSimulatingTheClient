package com.dmod.eagle.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.dmod.eagle.EagleLogic;

import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;

/**
 * The single hook of the whole mod.
 *
 * <p>{@code KeyboardInput#tick()} is where vanilla turns the physical keys into a {@link PlayerInput}
 * record; {@code ClientPlayerEntity} then reads {@code input.playerInput.sneak()} for its crouch
 * state, its movement speed and the input packet it sends to the server. Rewriting that record at
 * the tail of the method is therefore indistinguishable, from the rest of the game's point of view,
 * from the player having pressed shift themselves.
 *
 * <p>The mixin extends {@link Input} so the {@code playerInput} field can be written directly.
 */
@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin extends Input {

    @Inject(method = "tick", at = @At("TAIL"))
    private void eagle$afterKeyboardTick(CallbackInfo ci) {
        PlayerInput sampled = this.playerInput;

        // Record what the human is actually pressing, before we get a chance to overwrite it.
        EagleLogic.physicalSneak = sampled.sneak();

        if (EagleLogic.shouldSneak()) {
            this.playerInput = new PlayerInput(
                    sampled.forward(),
                    sampled.backward(),
                    sampled.left(),
                    sampled.right(),
                    sampled.jump(),
                    true,
                    sampled.sprint());
        }
    }
}
