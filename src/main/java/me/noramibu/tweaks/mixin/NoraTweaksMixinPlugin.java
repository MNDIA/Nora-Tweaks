package me.noramibu.tweaks.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class NoraTweaksMixinPlugin implements IMixinConfigPlugin {
    private static final boolean CATPUCCIN_LOADED = FabricLoader.getInstance().isModLoaded("catpuccin-addon");
    private static final boolean LOCATOR_BAR_EXISTS = classExists("net.minecraft.client.gui.hud.bar.LocatorBar");

    private static boolean classExists(String className) {
        try {
            Class.forName(className, false, NoraTweaksMixinPlugin.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".catpuccin.")) {
            return CATPUCCIN_LOADED;
        }
        if (mixinClassName.contains("LocatorBarMixin")) {
            return LOCATOR_BAR_EXISTS;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        if (LOCATOR_BAR_EXISTS) {
            return List.of("LocatorBarMixin");
        }
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
