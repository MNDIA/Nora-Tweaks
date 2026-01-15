/*
 * This code partially adapted from Meteor Rejects
 * Original source: https://github.com/AntiCope/meteor-rejects/
 * Credit: Meteor Rejects contributors
 * If Meteor Rejects gets updated, adapted features will get removed.
 */
package me.noramibu.tweaks.modules;

import baritone.api.BaritoneAPI;
import me.noramibu.tweaks.NoraTweaks;
import me.noramibu.tweaks.utils.Ore;
import me.noramibu.tweaks.utils.Seeds;
import me.noramibu.tweaks.utils.Seeds.Seed;
import me.noramibu.tweaks.utils.Seeds.SeedChangedEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.pathing.BaritoneUtils;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class OreSim extends Module {
    private final Map<Long, Map<Ore, Set<Vec3d>>> chunkRenderers = new ConcurrentHashMap<>();
    private Seed worldSeed;
    private Map<RegistryKey<Biome>, List<Ore>> oreConfig;
    private String lastWorldName;
    private RegistryKey<World> lastWorldKey;

    // Ore positions for Baritone integration (accessed by MineProcessMixin)
    public List<BlockPos> oreGoals = new ArrayList<>();

    public enum AirCheck {
        ON_LOAD,
        RECHECK,
        OFF
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> horizontalRadius = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-range")
        .description("Range of chunks to render around the player.")
        .defaultValue(5)
        .min(1)
        .sliderMax(10)
        .build());

    private final Setting<AirCheck> airCheck = sgGeneral.add(new EnumSetting.Builder<AirCheck>()
        .name("air-check-mode")
        .description("Checks for air blocks when validating simulated ore positions.")
        .defaultValue(AirCheck.RECHECK)
        .build());

    private final Setting<Boolean> baritone = sgGeneral.add(new BoolSetting.Builder()
        .name("baritone")
        .description("Set baritone ore positions to the simulated ones.")
        .defaultValue(true)
        .build());

    public boolean baritone() {
        return isActive() && baritone.get() && BaritoneUtils.IS_AVAILABLE;
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        meteordevelopment.meteorclient.gui.widgets.containers.WTable table = theme.table();
        table.add(theme.label("Supports: baritone-api, baritone-meteor, baritone-unoptimized. Recommended baritone-meteor for best compatibility")).expandX();
        table.row();
        table.add(theme.label("Doesn't support: baritone-standalone")).expandX();
        return table;
    }

    public OreSim() {
        super(NoraTweaks.CATEGORY, "ore-sim", "Simulates vanilla ore generation using the world seed.");
        SettingGroup sgOres = settings.createGroup("Ores");
        Ore.oreSettings.forEach(sgOres::add);
    }


    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || oreConfig == null) return;
        if (Seeds.get().getSeed() == null) return;

        int chunkX = mc.player.getChunkPos().x;
        int chunkZ = mc.player.getChunkPos().z;
        int rangeVal = horizontalRadius.get();

        for (int range = 0; range <= rangeVal; range++) {
            for (int x = -range + chunkX; x <= range + chunkX; x++) {
                renderChunk(x, chunkZ + range - rangeVal, event);
            }
            for (int x = -range + 1 + chunkX; x < range + chunkX; x++) {
                renderChunk(x, chunkZ - range + rangeVal + 1, event);
            }
        }
    }

    private void renderChunk(int x, int z, Render3DEvent event) {
        long chunkKey = ChunkPos.toLong(x, z);
        Map<Ore, Set<Vec3d>> chunk = chunkRenderers.get(chunkKey);
        if (chunk == null) return;

        for (Map.Entry<Ore, Set<Vec3d>> entry : chunk.entrySet()) {
            Ore ore = entry.getKey();
            if (!ore.active.get()) continue;
            for (Vec3d pos : entry.getValue()) {
                event.renderer.boxLines(pos.x, pos.y, pos.z, pos.x + 1, pos.y + 1, pos.z + 1, ore.color, 0);
            }
        }
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (airCheck.get() != AirCheck.RECHECK || event.newState.isOpaque()) return;
        long chunkKey = ChunkPos.toLong(event.pos);
        Map<Ore, Set<Vec3d>> chunk = chunkRenderers.get(chunkKey);
        if (chunk == null) return;
        Vec3d pos = Vec3d.of(event.pos);
        for (Set<Vec3d> ores : chunk.values()) {
            ores.remove(pos);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null || oreConfig == null) return;

        detectWorldChange();

        // Populate oreGoals for Baritone integration when mine process is active
        if (baritone() && BaritoneAPI.getProvider().getPrimaryBaritone().getMineProcess().isActive()) {
            oreGoals.clear();
            ChunkPos chunkPos = mc.player.getChunkPos();
            int rangeVal = 4;
            for (int range = 0; range <= rangeVal; range++) {
                for (int x = -range + chunkPos.x; x <= range + chunkPos.x; x++) {
                    oreGoals.addAll(addToBaritone(x, chunkPos.z + range - rangeVal));
                }
                for (int x = -range + 1 + chunkPos.x; x < range + chunkPos.x; x++) {
                    oreGoals.addAll(addToBaritone(x, chunkPos.z - range + rangeVal + 1));
                }
            }
        }
    }

    /**
     * Collect ore positions from a chunk for Baritone.
     */
    private ArrayList<BlockPos> addToBaritone(int chunkX, int chunkZ) {
        ArrayList<BlockPos> baritoneGoals = new ArrayList<>();
        long chunkKey = ChunkPos.toLong(chunkX, chunkZ);
        Map<Ore, Set<Vec3d>> chunk = chunkRenderers.get(chunkKey);
        if (chunk != null) {
            chunk.entrySet().stream()
                .filter(entry -> entry.getKey().active.get())
                .flatMap(entry -> entry.getValue().stream())
                .map(BlockPos::ofFloored)
                .forEach(baritoneGoals::add);
        }
        return baritoneGoals;
    }

    @Override
    public void onActivate() {
        if (Seeds.get().getSeed() == null) {
            error("No seed found. Run .seed-world <seed> to set one.");
            toggle();
            return;
        }
        updateWorldTracking();
        reload();
    }

    @Override
    public void onDeactivate() {
        chunkRenderers.clear();
        oreConfig = null;
        lastWorldName = null;
        lastWorldKey = null;
    }

    @EventHandler
    private void onSeedChanged(SeedChangedEvent event) {
        reload();
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        calculateChunk(event.chunk());
    }

    private void reload() {
        Seed seed = Seeds.get().getSeed();
        if (seed == null) return;
        worldSeed = seed;
        oreConfig = Ore.getRegistry(PlayerUtils.getDimension());
        chunkRenderers.clear();
        if (mc.world != null) {
            loadVisibleChunks();
        }
    }

    private void detectWorldChange() {
        if (mc.world == null) return;
        String currentWorld = Utils.getWorldName();
        RegistryKey<World> currentKey = mc.world.getRegistryKey();
        if (!Objects.equals(currentWorld, lastWorldName) || !Objects.equals(currentKey, lastWorldKey)) {
            lastWorldName = currentWorld;
            lastWorldKey = currentKey;
            reload();
        }
    }

    private void updateWorldTracking() {
        if (mc.world == null) {
            lastWorldName = null;
            lastWorldKey = null;
        } else {
            lastWorldName = Utils.getWorldName();
            lastWorldKey = mc.world.getRegistryKey();
        }
    }

    private void loadVisibleChunks() {
        if (mc.player == null) return;
        for (Chunk chunk : Utils.chunks(false)) {
            calculateChunk(chunk);
        }
    }

    private void calculateChunk(Chunk chunk) {
        if (chunk == null || mc.world == null || oreConfig == null || worldSeed == null) return;

        ChunkPos chunkPos = chunk.getPos();
        long chunkKey = chunkPos.toLong();
        if (chunkRenderers.containsKey(chunkKey)) return;

        Set<RegistryKey<Biome>> biomeKeys = new HashSet<>();
        ChunkPos.stream(chunkPos, 1).forEach(pos -> {
            Chunk neighbour = mc.world.getChunk(pos.x, pos.z, ChunkStatus.BIOMES, false);
            if (neighbour == null) return;
            for (ChunkSection section : neighbour.getSectionArray()) {
                section.getBiomeContainer().forEachValue(entry -> biomeKeys.add(entry.getKey().get()));
            }
        });

        Set<Ore> ores = biomeKeys.stream()
            .flatMap(biome -> getOresForBiome(biome).stream())
            .collect(Collectors.toSet());

        int chunkX = chunkPos.x << 4;
        int chunkZ = chunkPos.z << 4;
        ChunkRandom random = new ChunkRandom(ChunkRandom.RandomProvider.XOROSHIRO.create(0));
        long populationSeed = random.setPopulationSeed(worldSeed.seed, chunkX, chunkZ);

        Map<Ore, Set<Vec3d>> orePositions = new HashMap<>();
        for (Ore ore : ores) {
            HashSet<Vec3d> positions = new HashSet<>();
            random.setDecoratorSeed(populationSeed, ore.index, ore.step);
            int repeat = ore.count.get(random);

            for (int i = 0; i < repeat; i++) {
                if (ore.rarity != 1.0F && random.nextFloat() >= 1.0F / ore.rarity) continue;

                int x = random.nextInt(16) + chunkX;
                int z = random.nextInt(16) + chunkZ;
                int y = ore.heightProvider.get(random, ore.heightContext);
                BlockPos origin = new BlockPos(x, y, z);

                RegistryKey<Biome> biome = chunk.getBiomeForNoiseGen(x, y, z).getKey().get();
                if (!getOresForBiome(biome).contains(ore)) continue;

                if (ore.scattered) {
                    positions.addAll(generateHidden(mc.world, random, origin, ore.size));
                } else {
                    positions.addAll(generateNormal(mc.world, random, origin, ore.size, ore.discardOnAirChance));
                }
            }

            if (!positions.isEmpty()) {
                orePositions.put(ore, positions);
            }
        }

        if (!orePositions.isEmpty()) {
            chunkRenderers.put(chunkKey, orePositions);
        }
    }

    private List<Ore> getOresForBiome(RegistryKey<Biome> biomeKey) {
        if (oreConfig == null) return Collections.emptyList();
        List<Ore> ores = oreConfig.get(biomeKey);
        if (ores != null) return ores;
        return oreConfig.values().stream().findAny().orElse(Collections.emptyList());
    }

    private List<Vec3d> generateNormal(ClientWorld world, ChunkRandom random, BlockPos blockPos, int veinSize, float discardOnAir) {
        List<Vec3d> positions = new ArrayList<>();
        float angle = random.nextFloat() * (float) Math.PI;
        float spread = (float) veinSize / 8.0F;
        int padding = MathHelper.ceil(((float) veinSize / 16.0F * 2.0F + 1.0F) / 2.0F);
        double startX = blockPos.getX() + Math.sin(angle) * spread;
        double endX = blockPos.getX() - Math.sin(angle) * spread;
        double startZ = blockPos.getZ() + Math.cos(angle) * spread;
        double endZ = blockPos.getZ() - Math.cos(angle) * spread;
        double startY = blockPos.getY() + random.nextInt(3) - 2;
        double endY = blockPos.getY() + random.nextInt(3) - 2;
        int minX = blockPos.getX() - MathHelper.ceil(spread) - padding;
        int minY = blockPos.getY() - 2 - padding;
        int minZ = blockPos.getZ() - MathHelper.ceil(spread) - padding;
        int sizeX = 2 * (MathHelper.ceil(spread) + padding);
        int sizeY = 2 * (2 + padding);

        for (int x = minX; x <= minX + sizeX; x++) {
            for (int z = minZ; z <= minZ + sizeX; z++) {
                if (minY <= world.getTopY(Heightmap.Type.MOTION_BLOCKING, x, z)) {
                    return generateVein(world, random, veinSize, startX, endX, startZ, endZ, startY, endY, minX, minY, minZ, sizeX, sizeY, discardOnAir);
                }
            }
        }

        return positions;
    }

    private List<Vec3d> generateVein(ClientWorld world, ChunkRandom random, int veinSize, double startX, double endX, double startZ, double endZ, double startY, double endY, int minX, int minY, int minZ, int sizeX, int sizeY, float discardOnAir) {
        BitSet bitSet = new BitSet(sizeX * sizeY * sizeX);
        BlockPos.Mutable mutable = new BlockPos.Mutable();
        double[] buffer = new double[veinSize * 4];
        List<Vec3d> positions = new ArrayList<>();

        for (int i = 0; i < veinSize; i++) {
            float progress = (float) i / (float) veinSize;
            double x = MathHelper.lerp(progress, startX, endX);
            double y = MathHelper.lerp(progress, startY, endY);
            double z = MathHelper.lerp(progress, startZ, endZ);
            double scale = random.nextDouble() * veinSize / 16.0D;
            double radius = (MathHelper.sin((float) Math.PI * progress) + 1.0F) * scale + 1.0D;
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
            int minBlockX = Math.max(MathHelper.floor(centerX - radius), minX);
            int minBlockY = Math.max(MathHelper.floor(centerY - radius), minY);
            int minBlockZ = Math.max(MathHelper.floor(centerZ - radius), minZ);
            int maxBlockX = Math.max(MathHelper.floor(centerX + radius), minBlockX);
            int maxBlockY = Math.max(MathHelper.floor(centerY + radius), minBlockY);
            int maxBlockZ = Math.max(MathHelper.floor(centerZ + radius), minBlockZ);

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
                        mutable.set(x, y, z);
                        if (y < -64 || y >= 320) continue;
                        if (airCheck.get() != AirCheck.OFF && !world.getBlockState(mutable).isOpaque()) continue;
                        if (shouldPlace(world, mutable, discardOnAir, random)) {
                            positions.add(new Vec3d(x, y, z));
                        }
                    }
                }
            }
        }

        return positions;
    }

    private boolean shouldPlace(ClientWorld world, BlockPos pos, float discardOnAir, ChunkRandom random) {
        if (discardOnAir == 0 || (discardOnAir != 1.0F && random.nextFloat() >= discardOnAir)) return true;
        for (Direction direction : Direction.values()) {
            if (!world.getBlockState(pos.offset(direction)).isOpaque() && discardOnAir != 1.0F) return false;
        }
        return true;
    }

    private List<Vec3d> generateHidden(ClientWorld world, ChunkRandom random, BlockPos origin, int size) {
        List<Vec3d> positions = new ArrayList<>();
        int limit = random.nextInt(size + 1);
        for (int i = 0; i < limit; i++) {
            int range = Math.min(i, 7);
            int x = randomCoord(random, range) + origin.getX();
            int y = randomCoord(random, range) + origin.getY();
            int z = randomCoord(random, range) + origin.getZ();
            BlockPos pos = new BlockPos(x, y, z);
            if (airCheck.get() != AirCheck.OFF && !world.getBlockState(pos).isOpaque()) continue;
            if (shouldPlace(world, pos, 1.0F, random)) {
                positions.add(new Vec3d(x, y, z));
            }
        }
        return positions;
    }

    private int randomCoord(ChunkRandom random, int size) {
        return Math.round((random.nextFloat() - random.nextFloat()) * size);
    }
}
