package com.dmod.eagle.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.entity.Entity;

/**
 * Access to the shared-flags bits, which are {@code protected} on {@link Entity}.
 *
 * <p>Needed because of a vanilla asymmetry. {@code Entity#isGlowing()} reads the local {@code glowing}
 * field on the <em>server</em> but the tracked flag on the <em>client</em>:
 *
 * <pre>
 *   public boolean isGlowing() {
 *       return this.getWorld().isClient() ? this.getFlag(6) : this.glowing;
 *   }
 * </pre>
 *
 * <p>and {@code setGlowing} writes the local field first and then does
 * {@code setFlag(6, this.isGlowing())} - which on the client reads the flag back and stores it
 * unchanged. So {@code setGlowing} cannot turn an outline on client side; the flag has to be set
 * directly.
 */
@Mixin(Entity.class)
public interface EntityFlagsMixin {

    @Invoker("getFlag")
    boolean eagle$getFlag(int index);

    @Invoker("setFlag")
    void eagle$setFlag(int index, boolean value);
}
