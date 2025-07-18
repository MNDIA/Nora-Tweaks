package me.noramibu.tweaks;

import me.noramibu.tweaks.category.CustomCategoryManager;
import me.noramibu.tweaks.modules.AutoDirtPath;
import me.noramibu.tweaks.modules.AutoFarmLand;
import me.noramibu.tweaks.modules.CategoryManagerModule;
import me.noramibu.tweaks.modules.ChatUtility;
import me.noramibu.tweaks.modules.HotkeyUtility;
import me.noramibu.tweaks.modules.MaceKill;
import me.noramibu.tweaks.modules.WindChargeJump;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class NoraTweaks extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Nora Tweaks");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Nora's Tweaks");

        CustomCategoryManager.init();

        // Modules
        Modules.get().add(new AutoDirtPath());
        Modules.get().add(new AutoFarmLand());
        Modules.get().add(new CategoryManagerModule());
        Modules.get().add(new ChatUtility());
        Modules.get().add(new HotkeyUtility());
        Modules.get().add(new MaceKill());
        Modules.get().add(new WindChargeJump());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "me.noramibu.tweaks";
    }
}
