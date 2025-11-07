package me.noramibu.tweaks;

import me.noramibu.tweaks.category.CustomCategoryManager;
import me.noramibu.tweaks.modules.AutoDirtPath;
import me.noramibu.tweaks.modules.AutoFarm;
import me.noramibu.tweaks.modules.AutoFarmLand;
import me.noramibu.tweaks.modules.AutoLogStrip;
import me.noramibu.tweaks.modules.CategoryManagerModule;
import me.noramibu.tweaks.modules.ChatUtility;
import me.noramibu.tweaks.modules.HotkeyUtility;
import me.noramibu.tweaks.modules.LegitMaceKill;
import me.noramibu.tweaks.modules.MaceCombo;
import me.noramibu.tweaks.modules.WindChargeJump;
import me.noramibu.tweaks.modules.AutoTrapPlus;
import me.noramibu.tweaks.modules.SafePathing;
import me.noramibu.tweaks.modules.DeepslateESP;
import me.noramibu.tweaks.modules.OreSim;
import me.noramibu.tweaks.modules.PearlChecker;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;
import meteordevelopment.meteorclient.commands.Commands;
import me.noramibu.tweaks.commands.CalculatorCommand;
import me.noramibu.tweaks.commands.MobCheckerCommand;
import me.noramibu.tweaks.commands.LocateCommand;
import me.noramibu.tweaks.commands.SeedCommand;
import me.noramibu.tweaks.utils.Seeds;
import net.fabricmc.loader.api.FabricLoader;

public class NoraTweaks extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Nora Tweaks");
    public static final Category BARITONE_CATEGORY = new Category("Nora Tweaks - Baritone");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Nora's Tweaks");

        CustomCategoryManager.init();

        // Modules
        Modules.get().add(new AutoDirtPath());
        Modules.get().add(new AutoFarm());
        Modules.get().add(new AutoFarmLand());
        Modules.get().add(new AutoLogStrip());
        Modules.get().add(new CategoryManagerModule());
        Modules.get().add(new ChatUtility());
        Modules.get().add(new HotkeyUtility());
        Modules.get().add(new MaceCombo());
        Modules.get().add(new LegitMaceKill());
        Modules.get().add(new WindChargeJump());
        Modules.get().add(new AutoTrapPlus());
        Modules.get().add(new DeepslateESP());
        Modules.get().add(new PearlChecker());
        Modules.get().add(new OreSim());

        if (isBaritonePresent()) {
            Modules.get().add(new SafePathing());
        } else {
            LOG.warn("Baritone not loaded or API classes missing, skipping SafePathing module.");
        }

        // Commands
        Commands.add(new MobCheckerCommand());
        Commands.add(new CalculatorCommand());
        Commands.add(new SeedCommand());
        Commands.add(new LocateCommand());

        // Ensure seed system initializes
        Seeds.get();
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
        Modules.registerCategory(BARITONE_CATEGORY);
    }

    @Override
    public String getPackage() {
        return "me.noramibu.tweaks";
    }

    private boolean isBaritonePresent() {
        // Some builds use different mod IDs; check all known variants
        FabricLoader fl = FabricLoader.getInstance();
        boolean modPresent = fl.isModLoaded("baritone")
            || fl.isModLoaded("baritone-api-fabric")
            || fl.isModLoaded("baritone-standalone-fabric");
        if (!modPresent) return false;

        // Hard-check critical classes to avoid NoClassDefFoundError during module instantiation
        try {
            Class.forName("baritone.api.BaritoneAPI", false, NoraTweaks.class.getClassLoader());
            Class.forName("baritone.api.pathing.goals.Goal", false, NoraTweaks.class.getClassLoader());
            Class.forName("baritone.api.pathing.goals.GoalXZ", false, NoraTweaks.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            LOG.warn("Baritone present but API classes not found: {}: {}", t.getClass().getSimpleName(), t.getMessage());
            return false;
        }
    }
}
