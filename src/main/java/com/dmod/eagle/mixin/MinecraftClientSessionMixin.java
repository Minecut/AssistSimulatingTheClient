package com.dmod.eagle.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.session.Session;

/**
 * Write access to {@code MinecraftClient.session}.
 *
 * <p>The field is {@code private final}, so the setter needs {@code @Mutable} to have the final flag
 * stripped. Everything downstream - {@code getSession()}, the login handler, the integrated server -
 * reads that same field, which is why replacing it is enough to change the account.
 */
@Mixin(MinecraftClient.class)
public interface MinecraftClientSessionMixin {

    @Accessor("session")
    Session eagle$getSession();

    @Mutable
    @Accessor("session")
    void eagle$setSession(Session session);
}
