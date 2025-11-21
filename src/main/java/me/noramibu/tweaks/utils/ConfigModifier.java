package me.noramibu.tweaks.utils;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.config.Config;

public class ConfigModifier {
    private static ConfigModifier INSTANCE;

    public final SettingGroup sgNoraTweaks = Config.get().settings.createGroup("Nora Tweaks");

    public final Setting<Boolean> collectData = sgNoraTweaks.add(new BoolSetting.Builder()
            .name("collect-data")
            .description("Collects minimal data.\n\n" +
                    "Collects username, Minecraft, Meteor Client and Nora Tweaks version, Baritone availability.\n\n" +
                    "It doesn't collect: IP addresses, server addresses, timezone, or any other sensitive data.")
            .defaultValue(true)
            .onChanged(v -> {
                StartupDataCollector.get().collectData = v;
                StartupDataCollector.get().save();
            })
            .build()
    );

    public static ConfigModifier get() {
        if (INSTANCE == null) INSTANCE = new ConfigModifier();
        return INSTANCE;
    }
}

