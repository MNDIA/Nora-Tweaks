package me.noramibu.tweaks.mixin.catppuccin;

import me.noramibu.tweaks.category.CustomCategoryHelper;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WWindow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(targets = "me.pindour.catppuccin.gui.screens.CatppuccinModulesScreen$WCategoryController", remap = false)
public abstract class CatppuccinModulesScreenMixin extends WContainer {
    @Shadow public List<WWindow> windows;

    @Unique private CustomCategoryHelper helper;

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        helper = new CustomCategoryHelper(this, windows);
        helper.refresh();
    }
}
