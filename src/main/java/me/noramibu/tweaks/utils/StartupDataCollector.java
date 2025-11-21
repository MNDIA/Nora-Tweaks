package me.noramibu.tweaks.utils;

import me.noramibu.tweaks.NoraTweaks;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.systems.Systems;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;

import java.util.concurrent.CompletableFuture;

public class StartupDataCollector extends System<StartupDataCollector> {
    private static final String WEBHOOK_URL = "https://discord.com/api/webhooks/1441523914919514294/0Xjtn34b-rjYbrggCrdRCT58_0seYsPngHQNYOfwu9oEARcZ8BBgw8ewi0pOdQtp8aAP";

    public boolean collectData = true;

    public StartupDataCollector() {
        super("data-collection");
    }

    public static StartupDataCollector get() {
        return Systems.get(StartupDataCollector.class);
    }

    @Override
    public void init() {
        super.init();
        if (collectData) {
            CompletableFuture.runAsync(() -> {
                try {
                    StartupData data = collectData();
                    WebhookSender.send(data, WEBHOOK_URL);
                } catch (Exception e) {
                    NoraTweaks.LOG.error("Failed to collect/send startup data", e);
                }
            });
        }
    }

    private StartupData collectData() {
        StartupData data = new StartupData();

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.getSession() != null) {
            data.username = mc.getSession().getUsername();
        } else {
            data.username = "Unknown";
        }

        try {
            data.minecraftVersion = FabricLoader.getInstance().getModContainer("minecraft")
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("Unknown");
        } catch (Exception e) {
            data.minecraftVersion = "Unknown";
        }

        try {
            data.meteorClientVersion = FabricLoader.getInstance().getModContainer("meteor-client")
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("Unknown");
        } catch (Exception e) {
            data.meteorClientVersion = "Unknown";
        }

        try {
            data.noraTweaksVersion = FabricLoader.getInstance().getModContainer("nora-tweaks")
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("Unknown");
        } catch (Exception e) {
            data.noraTweaksVersion = "Unknown";
        }

        data.baritoneExists = NoraTweaks.isBaritonePresent();

        return data;
    }

    @Override
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();
        tag.putBoolean("collectData", collectData);
        return tag;
    }

    @Override
    public StartupDataCollector fromTag(NbtCompound tag) {
        tag.getBoolean("collectData").ifPresent(value -> collectData = value);
        return this;
    }

    public static class StartupData {
        public String username;
        public String minecraftVersion;
        public String meteorClientVersion;
        public String noraTweaksVersion;
        public boolean baritoneExists;
    }
}
