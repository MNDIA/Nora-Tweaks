package me.noramibu.tweaks.mixin;

import me.noramibu.tweaks.events.DoAttackEvent;
import meteordevelopment.meteorclient.MeteorClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class MinecraftClientMixin {
    
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void onDoAttack(CallbackInfoReturnable<Boolean> cir) {
        if (MeteorClient.EVENT_BUS.post(DoAttackEvent.get()).isCancelled()) {
            cir.setReturnValue(false);
        }
    }
}
