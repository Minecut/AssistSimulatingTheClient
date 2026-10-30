package com.dmod.eagle.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.dmod.eagle.gui.AccountScreen;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Puts the offline account button on the multiplayer screen.
 *
 * <p>The account the client connects with is decided when a connection is set up, so the place to
 * change it is where you pick a server. Nothing here rebuilds the list or touches vanilla's own
 * buttons - it appends one child after {@code init} has laid everything else out.
 */
@Mixin(MultiplayerScreen.class)
public abstract class MultiplayerScreenMixin extends Screen {

    protected MultiplayerScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void eagle$accountButton(CallbackInfo ci) {
        addDrawableChild(ButtonWidget.builder(
                        Text.literal("Offline Account"),
                        button -> this.client.setScreen(new AccountScreen(this)))
                .dimensions(6, 6, 110, 20)
                .build());
    }
}
