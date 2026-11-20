package com.slowbag.pulse.mixin;

import com.slowbag.pulse.client.audio.MusicBadgeManager;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @Inject(method = "getNameTag", at = @At("RETURN"), cancellable = true)
    private void musicplayer$appendNametagBadge(Entity entity, CallbackInfoReturnable<Component> cir) {
        if (entity instanceof Player player) {
            Component original = cir.getReturnValue();
            if (original != null) {
                Component decorated = MusicBadgeManager.decorateNametag(player, original);
                if (decorated != original) {
                    cir.setReturnValue(decorated);
                }
            }
        }
    }
}
