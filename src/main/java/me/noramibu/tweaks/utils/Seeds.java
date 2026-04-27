/*
 * This code partially adapted from Meteor Rejects
 * Original source: https://github.com/AntiCope/meteor-rejects/
 * Credit: Meteor Rejects contributors
 * If Meteor Rejects gets updated, adapted features will get removed.
 */
package me.noramibu.tweaks.utils;

import dev.xpple.cubiomes.Cubiomes;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import java.util.HashMap;
import java.util.Locale;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class Seeds extends System<Seeds> {
    private static final Seeds INSTANCE = new Seeds();
    private static final String DEFAULT_CUBIOMES_VERSION = "MC_1_21_11";

    public final HashMap<String, Seed> seeds = new HashMap<>();

    private Seeds() {
        super("seeds");
        init();
        load(MeteorClient.FOLDER);
    }

    public static Seeds get() {
        return INSTANCE;
    }

    public Seed getSeed() {
        if (mc == null) return null;

        if (mc.hasSingleplayerServer()) {
            if (mc.getSingleplayerServer() != null && mc.getSingleplayerServer().overworld() != null) {
                return new Seed(mc.getSingleplayerServer().overworld().getSeed(), resolveCubiomesVersion());
            }
            return null;
        }

        String worldName = Utils.getWorldName();
        if (worldName != null) {
            return seeds.get(worldName);
        }

        return null;
    }

    public void setSeed(String rawSeed) {
        if (mc == null || mc.hasSingleplayerServer()) return;

        ServerData server = mc.getCurrentServer();
        String verStr = server != null && server.version != null ? server.version.getString() : "unknown";
        setSeed(rawSeed, resolveCubiomesVersion(verStr));
    }

    public void setSeed(String rawSeed, String version) {
        if (mc == null || mc.hasSingleplayerServer()) return;

        String worldName = Utils.getWorldName();
        if (worldName == null) return;

        long numericSeed = parseSeed(rawSeed);
        seeds.put(worldName, new Seed(numericSeed, version));
        save();
        MeteorClient.EVENT_BUS.post(SeedChangedEvent.get(numericSeed));
    }

    public void removeSeed(String worldName) {
        if (worldName == null) return;
        if (seeds.remove(worldName) != null) {
            save();
        }
    }

    @Override
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        seeds.forEach((key, seed) -> {
            if (seed != null) {
                tag.put(key, seed.toTag());
            }
        });
        return tag;
    }

    @Override
    public Seeds fromTag(CompoundTag tag) {
        for (String key : tag.keySet()) {
            //? if >=1.21.5 {
            tag.getCompound(key).ifPresent(nbt -> seeds.put(key, Seed.fromTag(nbt)));
            //?} else {
            /*NbtCompound nbt = tag.getCompound(key);
            if (nbt != null) seeds.put(key, Seed.fromTag(nbt));
            */
            //?}
        }
        return this;
    }

    private static long parseSeed(String seed) {
        try {
            return Long.parseLong(seed);
        } catch (NumberFormatException ignored) {
            return seed.strip().hashCode();
        }
    }

    public static final class Seed {
        public final long seed;
        public final String version;

        public Seed(long seed, String version) {
            this.seed = seed;
            this.version = resolveStoredVersion(version);
        }

        public int cubiomesVersionId() {
            return resolveCubiomesVersionId(version);
        }

        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("seed", seed);
            tag.putString("version", version);
            return tag;
        }

        public static Seed fromTag(CompoundTag tag) {
            //? if >=1.21.5 {
            long storedSeed = tag.getLong("seed").orElse(0L);
            String versionName = tag.getString("version").orElse("");
            //?} else {
            /*long storedSeed = tag.getLong("seed");
            String versionName = tag.getString("version");
            */
            //?}
            String storedVersion = resolveStoredVersion(versionName);
            return new Seed(storedSeed, storedVersion);
        }

        public Component toText() {
            MutableComponent text = Component.literal(String.format("[%s%s%s] (%s)",
                ChatFormatting.GREEN,
                Long.toString(seed),
                ChatFormatting.WHITE,
                version
            ));

            //? if >=1.21.5 {
            text.setStyle(text.getStyle()
                .withClickEvent(new ClickEvent.CopyToClipboard(Long.toString(seed)))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Copy to clipboard"))));
            //?} else {
            /*text.setStyle(text.getStyle()
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, Long.toString(seed)))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("Copy to clipboard"))));
            */
            //?}

            return text;
        }
    }

    public static final class SeedChangedEvent {
        private static final SeedChangedEvent INSTANCE = new SeedChangedEvent();

        public long seed;

        public static SeedChangedEvent get(long seed) {
            INSTANCE.seed = seed;
            return INSTANCE;
        }
    }

    public static String getDefaultCubiomesVersion() {
        return DEFAULT_CUBIOMES_VERSION;
    }

    private static String resolveCubiomesVersion() {
        return DEFAULT_CUBIOMES_VERSION;
    }

    private static String resolveCubiomesVersion(String gameVer) {
        String parsed = resolveForPublic(gameVer);
        return parsed != null ? parsed : resolveCubiomesVersion();
    }

    private static String resolveStoredVersion(String input) {
        String parsed = resolveForPublic(input);
        return parsed != null ? parsed : resolveCubiomesVersion();
    }

    private static int resolveCubiomesVersionId(String versionName) {
        String resolved = resolveStoredVersion(versionName);
        try {
            return (int) Cubiomes.class.getMethod(resolved).invoke(null);
        } catch (ReflectiveOperationException ignored) {
            return Cubiomes.MC_1_21_11();
        }
    }

    public static String resolveForPublic(String input) {
        if (input == null || input.isBlank()) return null;

        String norm = input.trim()
            .toUpperCase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_');

        if (norm.matches("\\d+(\\.\\d+)+")) {
            norm = "MC_" + norm.replace('.', '_');
        }

        try {
            Cubiomes.class.getMethod(norm);
            return norm;
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}
