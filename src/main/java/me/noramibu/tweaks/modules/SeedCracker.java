package me.noramibu.tweaks.modules;

import me.noramibu.tweaks.NoraTweaks;
import me.noramibu.tweaks.utils.Ore;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public class SeedCracker extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSearch = settings.createGroup("Search");

    @SuppressWarnings("unused")
    private final Setting<Keybind> observeKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("observe-key")
        .description("Press while looking at an ore block to record it as an observation.")
        .defaultValue(Keybind.fromKey(GLFW.GLFW_KEY_O))
        .action(this::recordObservation)
        .build());

    private final Setting<Boolean> applyOnFound = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-apply-first")
        .description("Push the first matching seed straight into OreSim's custom-seed field.")
        .defaultValue(true)
        .build());

    private final Setting<String> searchFrom = sgSearch.add(new StringSetting.Builder()
        .name("search-from")
        .description("Lowest seed to test (inclusive). Accepts negative values.")
        .defaultValue("0")
        .build());

    private final Setting<String> searchTo = sgSearch.add(new StringSetting.Builder()
        .name("search-to")
        .description("Highest seed to test (inclusive).")
        .defaultValue("1000000")
        .build());

    private final Setting<Integer> threads = sgSearch.add(new IntSetting.Builder()
        .name("threads")
        .description("Worker threads. Brute force is CPU-bound; raise for big ranges.")
        .defaultValue(Math.max(1, Runtime.getRuntime().availableProcessors() / 2))
        .min(1)
        .sliderRange(1, Math.max(1, Runtime.getRuntime().availableProcessors()))
        .build());

    private final Setting<Integer> maxCandidates = sgSearch.add(new IntSetting.Builder()
        .name("max-candidates")
        .description("Stop after this many seeds match the observations.")
        .defaultValue(5)
        .min(1)
        .sliderMax(50)
        .build());

    public static final class Observation {
        public final BlockPos pos;
        public final Ore.OreType type;

        public Observation(BlockPos pos, Ore.OreType type) {
            this.pos = pos.immutable();
            this.type = type;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Observation other)) return false;
            return type == other.type && pos.equals(other.pos);
        }

        @Override
        public int hashCode() {
            return Objects.hash(pos, type);
        }
    }

    private final List<Observation> observations = new ArrayList<>();
    private final List<Long> candidates = new ArrayList<>();
    private final ConcurrentLinkedQueue<Long> pendingCandidates = new ConcurrentLinkedQueue<>();

    private ExecutorService searchPool;
    private final List<Future<?>> searchTasks = new ArrayList<>();
    private final AtomicLong searchProgress = new AtomicLong();
    private volatile long searchTotal;
    private volatile boolean searching;
    private volatile boolean cancelRequested;

    private WTable rootTable;
    private WLabel statusLabel;
    private WTable observationsTable;
    private WTable candidatesTable;
    private WButton searchButton;

    public SeedCracker() {
        super(NoraTweaks.CATEGORY, "seed-cracker", "Brute-forces a world seed from clicked ore blocks and feeds it into OreSim.");
    }

    @Override
    public void onDeactivate() {
        cancelSearch();
    }

    private void recordObservation() {
        if (!isActive()) return;
        if (mc.level == null) return;
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult block) || block.getType() != HitResult.Type.BLOCK) {
            info("Look at an ore block first.");
            return;
        }
        BlockPos pos = block.getBlockPos();
        BlockState state = mc.level.getBlockState(pos);
        Ore.OreType type = Ore.typeFor(state.getBlock());
        if (type == null) {
            info("That block isn't a tracked ore.");
            return;
        }
        Observation obs = new Observation(pos, type);
        if (observations.contains(obs)) {
            info("Already observed " + type + " at " + posToString(pos) + ".");
            return;
        }
        observations.add(obs);
        info("Observed " + type + " at " + posToString(pos) + " (" + observations.size() + " total).");
        if (rootTable != null) rebuildWidget();
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        rootTable = theme.table();
        rebuildWidget(theme);
        return rootTable;
    }

    private void rebuildWidget() {
        if (rootTable == null) return;
        rebuildWidget(rootTable.theme);
    }

    private void rebuildWidget(GuiTheme theme) {
        rootTable.clear();

        rootTable.add(theme.label("Observations: " + observations.size())).expandX();
        WButton clearObs = theme.button("Clear");
        clearObs.action = () -> { observations.clear(); rebuildWidget(theme); };
        rootTable.add(clearObs);
        rootTable.row();

        observationsTable = theme.table();
        rootTable.add(observationsTable).expandX().minWidth(280);
        rootTable.row();

        for (Observation obs : new ArrayList<>(observations)) {
            observationsTable.add(theme.label(obs.type + "  " + posToString(obs.pos)));
            WMinus remove = theme.minus();
            remove.action = () -> { observations.remove(obs); rebuildWidget(theme); };
            observationsTable.add(remove);
            observationsTable.row();
        }

        statusLabel = theme.label(buildStatusText());
        rootTable.add(statusLabel).expandX();
        rootTable.row();

        searchButton = theme.button(searching ? "Cancel" : "Start search");
        searchButton.action = () -> {
            if (searching) cancelSearch();
            else startSearch();
        };
        rootTable.add(searchButton).expandX();
        rootTable.row();

        candidatesTable = theme.table();
        rootTable.add(candidatesTable).expandX().minWidth(280);
        rootTable.row();

        synchronized (candidates) {
            for (Long seed : new ArrayList<>(candidates)) {
                candidatesTable.add(theme.label("seed: " + seed));
                WButton apply = theme.button("Apply to OreSim");
                apply.action = () -> applyToOreSim(seed);
                candidatesTable.add(apply);
                candidatesTable.row();
            }
        }
    }

    private void applyToOreSim(long seed) {
        OreSim oreSim = Modules.get().get(OreSim.class);
        if (oreSim == null) {
            error("OreSim module not found.");
            return;
        }
        oreSim.applyCustomSeed(seed);
        if (!oreSim.isActive()) oreSim.toggle();
        info("Applied seed " + seed + " to OreSim.");
    }

    private String buildStatusText() {
        if (!searching) {
            int found;
            synchronized (candidates) { found = candidates.size(); }
            return "Idle. " + found + " candidates found.";
        }
        long done = searchProgress.get();
        long total = Math.max(1, searchTotal);
        int pct = (int) ((done * 100L) / total);
        int found;
        synchronized (candidates) { found = candidates.size(); }
        return "Searching " + done + "/" + searchTotal + " (" + pct + "%) — " + found + " found.";
    }

    private void startSearch() {
        if (observations.isEmpty()) {
            error("Record at least one ore observation first.");
            return;
        }
        if (mc.level == null || mc.player == null) {
            error("Must be in a world.");
            return;
        }

        long from = parseSeed(searchFrom.get(), 0L);
        long to = parseSeed(searchTo.get(), 1_000_000L);
        if (to < from) {
            error("search-to must be >= search-from.");
            return;
        }

        Map<ResourceKey<Biome>, List<Ore>> oreConfig = Ore.getRegistry(PlayerUtils.getDimension());

        Map<Long, ChunkSnapshot> snapshots = new HashMap<>();
        for (Observation obs : observations) {
            long key = ChunkPos.pack(obs.pos.getX() >> 4, obs.pos.getZ() >> 4);
            if (snapshots.containsKey(key)) continue;
            ChunkSnapshot snap = snapshotChunk(obs.pos.getX() >> 4, obs.pos.getZ() >> 4, oreConfig);
            if (snap == null) {
                error("Chunk at " + (obs.pos.getX() >> 4) + ", " + (obs.pos.getZ() >> 4) + " is not loaded.");
                return;
            }
            snapshots.put(key, snap);
        }

        Map<Long, List<Observation>> obsByChunk = new HashMap<>();
        for (Observation obs : observations) {
            long key = ChunkPos.pack(obs.pos.getX() >> 4, obs.pos.getZ() >> 4);
            obsByChunk.computeIfAbsent(key, k -> new ArrayList<>()).add(obs);
        }

        synchronized (candidates) { candidates.clear(); }
        pendingCandidates.clear();
        searchProgress.set(0);
        searchTotal = to - from + 1;
        cancelRequested = false;
        searching = true;

        int t = Math.max(1, threads.get());
        searchPool = Executors.newFixedThreadPool(t, r -> {
            Thread thread = new Thread(r, "SeedCracker-Worker");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        searchTasks.clear();

        long range = to - from + 1;
        long chunkSize = (range + t - 1) / t;
        for (int i = 0; i < t; i++) {
            long start = from + (long) i * chunkSize;
            long end = Math.min(to, start + chunkSize - 1);
            if (start > end) break;
            searchTasks.add(searchPool.submit(() -> runWorker(start, end, snapshots, obsByChunk)));
        }

        Thread monitor = new Thread(this::monitorSearch, "SeedCracker-Monitor");
        monitor.setDaemon(true);
        monitor.start();

        rebuildWidget();
    }

    private void monitorSearch() {
        while (searching) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) { return; }
            boolean alive = false;
            for (Future<?> f : searchTasks) if (!f.isDone()) { alive = true; break; }
            mc.execute(() -> {
                if (statusLabel != null) statusLabel.set(buildStatusText());
                drainPending();
            });
            if (!alive) break;
        }
        searching = false;
        mc.execute(() -> {
            drainPending();
            if (statusLabel != null) statusLabel.set(buildStatusText());
            if (searchButton != null) searchButton.set("Start search");
            rebuildWidget();
            int found;
            synchronized (candidates) { found = candidates.size(); }
            info("Search done. " + found + " candidate(s).");
        });
    }

    private void drainPending() {
        Long s;
        while ((s = pendingCandidates.poll()) != null) {
            boolean firstFound;
            synchronized (candidates) {
                if (candidates.contains(s)) continue;
                firstFound = candidates.isEmpty();
                candidates.add(s);
            }
            if (firstFound && applyOnFound.get()) applyToOreSim(s);
        }
    }

    private void cancelSearch() {
        if (!searching && searchPool == null) return;
        cancelRequested = true;
        searching = false;
        if (searchPool != null) {
            searchPool.shutdownNow();
            searchPool = null;
        }
        searchTasks.clear();
        if (searchButton != null) searchButton.set("Start search");
        if (statusLabel != null) statusLabel.set("Cancelled.");
    }

    private void runWorker(long from, long to, Map<Long, ChunkSnapshot> snapshots, Map<Long, List<Observation>> obsByChunk) {
        WorldgenRandom random = new WorldgenRandom(WorldgenRandom.Algorithm.XOROSHIRO.newInstance(0));
        long localProgress = 0;
        for (long seed = from; seed <= to; seed++) {
            if (cancelRequested) return;
            if (matches(seed, snapshots, obsByChunk, random)) {
                pendingCandidates.add(seed);
                synchronized (candidates) {
                    if (candidates.size() + pendingCandidates.size() >= maxCandidates.get()) {
                        cancelRequested = true;
                    }
                }
            }
            localProgress++;
            if ((localProgress & 0xFFFL) == 0) {
                searchProgress.addAndGet(localProgress);
                localProgress = 0;
            }
        }
        searchProgress.addAndGet(localProgress);
    }

    private boolean matches(long seed, Map<Long, ChunkSnapshot> snapshots, Map<Long, List<Observation>> obsByChunk, WorldgenRandom random) {
        for (Map.Entry<Long, List<Observation>> entry : obsByChunk.entrySet()) {
            ChunkSnapshot snap = snapshots.get(entry.getKey());
            List<Observation> chunkObs = entry.getValue();
            Map<Ore.OreType, Set<BlockPos>> needed = new HashMap<>();
            for (Observation obs : chunkObs) {
                needed.computeIfAbsent(obs.type, k -> new HashSet<>()).add(obs.pos);
            }
            Map<Ore.OreType, Set<BlockPos>> generated = simulateChunk(seed, snap, needed.keySet(), random);
            for (Map.Entry<Ore.OreType, Set<BlockPos>> need : needed.entrySet()) {
                Set<BlockPos> gen = generated.getOrDefault(need.getKey(), Collections.emptySet());
                for (BlockPos p : need.getValue()) {
                    if (!gen.contains(p)) return false;
                }
            }
        }
        return true;
    }

    private Map<Ore.OreType, Set<BlockPos>> simulateChunk(long seed, ChunkSnapshot snap, Set<Ore.OreType> requestedTypes, WorldgenRandom random) {
        Map<Ore.OreType, Set<BlockPos>> result = new HashMap<>();
        int chunkX = snap.chunkX << 4;
        int chunkZ = snap.chunkZ << 4;
        long populationSeed = random.setDecorationSeed(seed, chunkX, chunkZ);

        for (Ore ore : snap.ores) {
            if (!requestedTypes.contains(ore.type)) continue;
            random.setFeatureSeed(populationSeed, ore.index, ore.step);
            int repeat = ore.count.sample(random);
            Set<BlockPos> positions = result.computeIfAbsent(ore.type, k -> new HashSet<>());

            for (int i = 0; i < repeat; i++) {
                if (ore.rarity != 1.0F && random.nextFloat() >= 1.0F / ore.rarity) continue;
                int x = random.nextInt(16) + chunkX;
                int z = random.nextInt(16) + chunkZ;
                int y = ore.heightProvider.sample(random, ore.heightContext);

                ResourceKey<Biome> biome = snap.biomeAt(x, y, z);
                if (biome == null) continue;
                List<Ore> biomeOres = snap.oresByBiome.get(biome);
                if (biomeOres == null || !biomeOres.contains(ore)) continue;

                if (ore.scattered) {
                    simulateScattered(random, x, y, z, ore.size, positions);
                } else {
                    simulateNormal(random, x, y, z, ore.size, ore.discardOnAirChance, snap, positions);
                }
            }
        }
        return result;
    }

    private static void simulateNormal(WorldgenRandom random, int originX, int originY, int originZ, int veinSize, float discardOnAir, ChunkSnapshot snap, Set<BlockPos> out) {
        float angle = random.nextFloat() * (float) Math.PI;
        float spread = (float) veinSize / 8.0F;
        int padding = Mth.ceil(((float) veinSize / 16.0F * 2.0F + 1.0F) / 2.0F);
        double startX = originX + Math.sin(angle) * spread;
        double endX = originX - Math.sin(angle) * spread;
        double startZ = originZ + Math.cos(angle) * spread;
        double endZ = originZ - Math.cos(angle) * spread;
        double startY = originY + random.nextInt(3) - 2;
        double endY = originY + random.nextInt(3) - 2;
        int minX = originX - Mth.ceil(spread) - padding;
        int minY = originY - 2 - padding;
        int minZ = originZ - Mth.ceil(spread) - padding;
        int sizeX = 2 * (Mth.ceil(spread) + padding);
        int sizeY = 2 * (2 + padding);

        boolean anyAbove = false;
        for (int x = minX; x <= minX + sizeX && !anyAbove; x++) {
            for (int z = minZ; z <= minZ + sizeX && !anyAbove; z++) {
                int h = snap.heightAt(x, z);
                if (h != Integer.MIN_VALUE && minY <= h) anyAbove = true;
            }
        }
        if (!anyAbove) return;

        BitSet bitSet = new BitSet(sizeX * sizeY * sizeX);
        double[] buffer = new double[veinSize * 4];

        for (int i = 0; i < veinSize; i++) {
            float progress = (float) i / (float) veinSize;
            double x = Mth.lerp(progress, startX, endX);
            double y = Mth.lerp(progress, startY, endY);
            double z = Mth.lerp(progress, startZ, endZ);
            double scale = random.nextDouble() * veinSize / 16.0D;
            double radius = (Mth.sin((float) Math.PI * progress) + 1.0F) * scale + 1.0D;
            buffer[i * 4] = x;
            buffer[i * 4 + 1] = y;
            buffer[i * 4 + 2] = z;
            buffer[i * 4 + 3] = radius / 2.0D;
        }

        for (int i = 0; i < veinSize - 1; i++) {
            if (buffer[i * 4 + 3] <= 0.0D) continue;
            for (int j = i + 1; j < veinSize; j++) {
                if (buffer[j * 4 + 3] <= 0.0D) continue;
                double dx = buffer[i * 4] - buffer[j * 4];
                double dy = buffer[i * 4 + 1] - buffer[j * 4 + 1];
                double dz = buffer[i * 4 + 2] - buffer[j * 4 + 2];
                double dr = buffer[i * 4 + 3] - buffer[j * 4 + 3];
                if (dr * dr > dx * dx + dy * dy + dz * dz) {
                    if (dr > 0.0D) buffer[j * 4 + 3] = -1.0D;
                    else buffer[i * 4 + 3] = -1.0D;
                }
            }
        }

        for (int i = 0; i < veinSize; i++) {
            double radius = buffer[i * 4 + 3];
            if (radius < 0.0D) continue;
            double centerX = buffer[i * 4];
            double centerY = buffer[i * 4 + 1];
            double centerZ = buffer[i * 4 + 2];
            int minBlockX = Math.max(Mth.floor(centerX - radius), minX);
            int minBlockY = Math.max(Mth.floor(centerY - radius), minY);
            int minBlockZ = Math.max(Mth.floor(centerZ - radius), minZ);
            int maxBlockX = Math.max(Mth.floor(centerX + radius), minBlockX);
            int maxBlockY = Math.max(Mth.floor(centerY + radius), minBlockY);
            int maxBlockZ = Math.max(Mth.floor(centerZ + radius), minBlockZ);

            for (int x = minBlockX; x <= maxBlockX; x++) {
                double normX = ((double) x + 0.5D - centerX) / radius;
                if (normX * normX >= 1.0D) continue;
                for (int y = minBlockY; y <= maxBlockY; y++) {
                    double normY = ((double) y + 0.5D - centerY) / radius;
                    if (normX * normX + normY * normY >= 1.0D) continue;
                    for (int z = minBlockZ; z <= maxBlockZ; z++) {
                        double normZ = ((double) z + 0.5D - centerZ) / radius;
                        if (normX * normX + normY * normY + normZ * normZ >= 1.0D) continue;
                        int index = x - minX + (y - minY) * sizeX + (z - minZ) * sizeX * sizeY;
                        if (bitSet.get(index)) continue;
                        bitSet.set(index);
                        if (y < -64 || y >= 320) continue;
                        if (shouldPlaceSim(discardOnAir, random)) {
                            out.add(new BlockPos(x, y, z));
                        }
                    }
                }
            }
        }
    }

    private static boolean shouldPlaceSim(float discardOnAir, WorldgenRandom random) {
        if (discardOnAir == 0) return true;
        if (discardOnAir != 1.0F && random.nextFloat() >= discardOnAir) return true;
        return true;
    }

    private static void simulateScattered(WorldgenRandom random, int originX, int originY, int originZ, int size, Set<BlockPos> out) {
        int limit = random.nextInt(size + 1);
        for (int i = 0; i < limit; i++) {
            int range = Math.min(i, 7);
            int x = Math.round((random.nextFloat() - random.nextFloat()) * range) + originX;
            int y = Math.round((random.nextFloat() - random.nextFloat()) * range) + originY;
            int z = Math.round((random.nextFloat() - random.nextFloat()) * range) + originZ;
            out.add(new BlockPos(x, y, z));
        }
    }

    private ChunkSnapshot snapshotChunk(int chunkX, int chunkZ, Map<ResourceKey<Biome>, List<Ore>> oreConfig) {
        if (mc.level == null) return null;
        ChunkAccess chunk = mc.level.getChunk(chunkX, chunkZ, ChunkStatus.BIOMES, false);
        if (chunk == null) return null;

        Set<ResourceKey<Biome>> biomeKeys = new HashSet<>();
        ChunkPos.rangeClosed(chunk.getPos(), 1).forEach(pos -> {
            ChunkAccess neighbour = mc.level.getChunk(pos.x(), pos.z(), ChunkStatus.BIOMES, false);
            if (neighbour == null) return;
            for (LevelChunkSection section : neighbour.getSections()) {
                section.getBiomes().getAll(entry -> entry.unwrapKey().ifPresent(biomeKeys::add));
            }
        });

        Set<Ore> ores = new HashSet<>();
        for (ResourceKey<Biome> biomeKey : biomeKeys) {
            List<Ore> biomeOres = oreConfig.get(biomeKey);
            if (biomeOres != null) ores.addAll(biomeOres);
        }

        int minY = mc.level.getMinY();
        int maxY = mc.level.getMaxY();

        Map<Long, ResourceKey<Biome>> biomeCache = new HashMap<>();
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int x = baseX; x < baseX + 16; x += 4) {
            for (int z = baseZ; z < baseZ + 16; z += 4) {
                for (int y = minY; y <= maxY; y += 4) {
                    ResourceKey<Biome> bk = chunk.getNoiseBiome(x, y, z).unwrapKey().orElse(null);
                    if (bk != null) biomeCache.put(noiseKey(x, y, z), bk);
                }
            }
        }

        int[] heightMap = new int[16 * 16];
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                heightMap[dx * 16 + dz] = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, baseX + dx, baseZ + dz);
            }
        }

        ChunkSnapshot snap = new ChunkSnapshot();
        snap.chunkX = chunkX;
        snap.chunkZ = chunkZ;
        snap.ores = new ArrayList<>(ores);
        snap.oresByBiome = oreConfig;
        snap.biomeCache = biomeCache;
        snap.heightMap = heightMap;
        snap.minY = minY;
        snap.maxY = maxY;
        return snap;
    }

    private static long noiseKey(int x, int y, int z) {
        return ((long) (x >> 2) & 0x1FFFFFL) | (((long) (z >> 2) & 0x1FFFFFL) << 21) | (((long) (y >> 2) & 0x3FFFFFL) << 42);
    }

    private static long parseSeed(String input, long fallback) {
        try {
            return Long.parseLong(input.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String posToString(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    public static final class ChunkSnapshot {
        int chunkX;
        int chunkZ;
        List<Ore> ores;
        Map<ResourceKey<Biome>, List<Ore>> oresByBiome;
        Map<Long, ResourceKey<Biome>> biomeCache;
        int[] heightMap;
        int minY;
        int maxY;

        ResourceKey<Biome> biomeAt(int x, int y, int z) {
            int qy = Math.max(minY, Math.min(maxY, y));
            return biomeCache.get(noiseKey(x, qy, z));
        }

        int heightAt(int x, int z) {
            int dx = x - (chunkX << 4);
            int dz = z - (chunkZ << 4);
            if (dx < 0 || dx > 15 || dz < 0 || dz > 15) return Integer.MIN_VALUE;
            return heightMap[dx * 16 + dz];
        }
    }
}
