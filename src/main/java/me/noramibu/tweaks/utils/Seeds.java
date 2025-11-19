/*
 * This code partially adapted from Meteor Rejects
 * Original source: https://github.com/AntiCope/meteor-rejects/
 * Credit: Meteor Rejects contributors
 * If Meteor Rejects gets updated, adapted features will get removed.
 */
package me.noramibu.tweaks.utils;

import cubitect.Cubiomes;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.System;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.HashMap;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class Seeds extends System<Seeds> {
    private static final Seeds INSTANCE = new Seeds();

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

        if (mc.isIntegratedServerRunning()) {
            if (mc.getServer() != null && mc.getServer().getOverworld() != null) {
                return new Seed(mc.getServer().getOverworld().getSeed(), resolveCubiomesVersion());
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
        if (mc == null || mc.isIntegratedServerRunning()) return;

        ServerInfo server = mc.getCurrentServerEntry();
        String verStr = server != null && server.version != null ? server.version.getString() : "unknown";
        setSeed(rawSeed, resolveCubiomesVersion(verStr));
    }

    public void setSeed(String rawSeed, Cubiomes.MCVersion version) {
        if (mc == null || mc.isIntegratedServerRunning()) return;

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
    public NbtCompound toTag() {
        NbtCompound tag = new NbtCompound();
        seeds.forEach((key, seed) -> {
            if (seed != null) {
                tag.put(key, seed.toTag());
            }
        });
        return tag;
    }

    @Override
    public Seeds fromTag(NbtCompound tag) {
        for (String key : tag.getKeys()) {
            tag.getCompound(key).ifPresent(nbt -> seeds.put(key, Seed.fromTag(nbt)));
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
        public final Cubiomes.MCVersion version;

        public Seed(long seed, Cubiomes.MCVersion version) {
            this.seed = seed;
            this.version = version == null ? resolveCubiomesVersion() : version;
        }

        public NbtCompound toTag() {
            NbtCompound tag = new NbtCompound();
            tag.putLong("seed", seed);
            tag.putString("version", version.name());
            return tag;
        }

        public static Seed fromTag(NbtCompound tag) {
            long storedSeed = tag.getLong("seed").orElse(0L);
            String versionName = tag.getString("version").orElse("");
            Cubiomes.MCVersion storedVersion = parseCubiomesVersion(versionName);
            return new Seed(storedSeed, storedVersion);
        }

        public Text toText() {
            MutableText text = Text.literal(String.format("[%s%s%s] (%s)",
                Formatting.GREEN,
                Long.toString(seed),
                Formatting.WHITE,
                version.name()
            ));

            text.setStyle(text.getStyle()
                .withClickEvent(new ClickEvent.CopyToClipboard(Long.toString(seed)))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal("Copy to clipboard"))));

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

    private static Cubiomes.MCVersion resolveCubiomesVersion() {
        return Cubiomes.MCVersion.MC_1_21_WD;
    }

    private static Cubiomes.MCVersion resolveCubiomesVersion(String gameVer) {
        return Cubiomes.MCVersion.MC_1_21_WD;
    }

    private static Cubiomes.MCVersion parseCubiomesVersion(String input) {
        if (input == null || input.isEmpty()) return resolveCubiomesVersion();
        String norm = input.trim().toUpperCase();
        try {
            return Cubiomes.MCVersion.valueOf(norm);
        } catch (IllegalArgumentException ignored) {}
        return resolveCubiomesVersion();
    }

    public static Cubiomes.MCVersion resolveForPublic(String input) {
        return parseCubiomesVersion(input);
    }
}
