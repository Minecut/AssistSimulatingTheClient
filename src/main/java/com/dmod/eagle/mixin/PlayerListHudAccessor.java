package com.dmod.eagle.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.text.Text;

/**
 * Reads the header and footer the server pushed to the tab list.
 *
 * <p>There are setters but no getters, and the two fields are private. The island's own panel shows
 * that text, so it has to be readable.
 */
@Mixin(PlayerListHud.class)
public interface PlayerListHudAccessor {

    @Accessor("header")
    Text eagle$header();

    @Accessor("footer")
    Text eagle$footer();
}
