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
import me.noramibu.tweaks.modules.DeepslateESP;
import me.noramibu.tweaks.modules.OreSim;
import me.noramibu.tweaks.modules.OreSimBaritone;
import me.noramibu.tweaks.modules.PearlChecker;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import me.noramibu.tweaks.utils.ConfigModifier;
import meteordevelopment.meteorclient.systems.Systems;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;
import meteordevelopment.meteorclient.commands.Commands;
import me.noramibu.tweaks.commands.CalculatorCommand;
import me.noramibu.tweaks.commands.MobCheckerCommand;
import me.noramibu.tweaks.commands.LocateCommand;
import me.noramibu.tweaks.commands.SeedCommand;
import me.noramibu.tweaks.utils.Seeds;
import me.noramibu.tweaks.utils.StartupDataCollector;

public class NoraTweaks extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Nora Tweaks");

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

        // Conditionally register OreSimBaritone if Baritone is available
        if (isBaritonePresent()) {
            Modules.get().add(new OreSimBaritone());
        } else {
            LOG.warn("Baritone not loaded or API classes missing, skipping OreSim with Baritone module.");
            Modules.get().add(new OreSim());
        }

        Commands.add(new MobCheckerCommand());
        Commands.add(new CalculatorCommand());
        Commands.add(new SeedCommand());
        Commands.add(new LocateCommand());

        Seeds.get();

        Systems.add(new StartupDataCollector());
        
        ConfigModifier.get();
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "me.noramibu.tweaks";
    }

    public static boolean isBaritonePresent() {
        try {
            Class.forName("baritone.api.pathing.goals.Goal");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
