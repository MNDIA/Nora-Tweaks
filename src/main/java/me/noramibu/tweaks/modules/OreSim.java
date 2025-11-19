/*
 * This code partially adapted from Meteor Rejects
 * Original source: https://github.com/AntiCope/meteor-rejects/
 * Credit: Meteor Rejects contributors
 * If Meteor Rejects gets updated, adapted features will get removed.
 */
package me.noramibu.tweaks.modules;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.process.ICustomGoalProcess;
import baritone.api.process.IMineProcess;
import baritone.api.utils.BlockOptionalMeta;
import baritone.api.utils.BlockOptionalMetaLookup;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
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
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
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
    private boolean ourMiningActive = false;

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

    public OreSim() {
        super(NoraTweaks.CATEGORY, "ore-sim", "Simulates vanilla ore generation using the world seed.");
        SettingGroup sgOres = settings.createGroup("Ores");
        Ore.oreSettings.forEach(sgOres::add);
    }
    
    @Override
    public WWidget getWidget(GuiTheme theme) {
        if (!BaritoneUtils.IS_AVAILABLE) return null;
        
        WTable table = theme.table();
        
        WButton startButton = table.add(theme.button("Start Baritone Goal")).expandX().widget();
        startButton.action = () -> startBaritoneGoal();
        
        WButton stopButton = table.add(theme.button("Stop Baritone Goal")).expandX().widget();
        stopButton.action = () -> stopBaritoneGoal();
        
        return table;
    }
    
    private void startBaritoneGoal() {
        if (!isActive()) {
            error("OreSim module is not active");
            return;
        }
        
        if (!BaritoneUtils.IS_AVAILABLE) {
            error("Baritone is not available");
            return;
        }
        
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone == null) {
            error("Baritone instance not found");
            return;
        }
        
        IMineProcess mineProcess = baritone.getMineProcess();
        if (mineProcess == null) {
            error("Mine process not found");
            return;
        }
        
        if (mineProcess.isActive()) {
            mineProcess.cancel();
        }
        
        startCustomGoal(baritone);
        ourMiningActive = true;
    }
    
    private void stopBaritoneGoal() {
        IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
        if (baritone == null) return;
        
        ICustomGoalProcess customGoalProcess = baritone.getCustomGoalProcess();
        if (customGoalProcess != null) {
            customGoalProcess.setGoal(null);
        }
        
        ourMiningActive = false;
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

        if (ourMiningActive && BaritoneUtils.IS_AVAILABLE) {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (baritone == null) {
                ourMiningActive = false;
                return;
            }
            
            IMineProcess mineProcess = baritone.getMineProcess();
            if (mineProcess == null) {
                ourMiningActive = false;
                return;
            }
            
            ICustomGoalProcess customGoalProcess = baritone.getCustomGoalProcess();
            boolean isCustomGoalActive = customGoalProcess != null && customGoalProcess.isActive();
            
            if (!isCustomGoalActive) {
                ourMiningActive = false;
                return;
            }
            
            if (mineProcess.isActive()) {
                mineProcess.cancel();
            }
            
            updateCustomGoal(baritone);
        }
    }
    
    private void startCustomGoal(IBaritone baritone) {
        List<BlockPos> positions = collectOrePositions();
        
        if (positions.isEmpty()) {
            error("No ore positions found. Make sure you have a seed set and ore types enabled.");
            return;
        }
        
        positions.sort((pos1, pos2) -> {
            //? if >=1.21.10 {
            Vec3d playerPos = mc.player.getEntityPos();
            //?} else
            /*Vec3d playerPos = mc.player.getPos();
            */
            double dist1 = playerPos.squaredDistanceTo(Vec3d.ofCenter(pos1));
            double dist2 = playerPos.squaredDistanceTo(Vec3d.ofCenter(pos2));
            return Double.compare(dist1, dist2);
        });
        
        IMineProcess mineProcess = baritone.getMineProcess();
        List<BlockOptionalMeta> activeOreBlocks = getActiveOreBlocks();
        if (!activeOreBlocks.isEmpty() && mineProcess != null) {
            BlockOptionalMetaLookup lookup = new BlockOptionalMetaLookup(activeOreBlocks.toArray(new BlockOptionalMeta[0]));
            mineProcess.mine(lookup);
        }
        
        ICustomGoalProcess customGoalProcess = baritone.getCustomGoalProcess();
        if (customGoalProcess != null) {
            Goal[] goals = positions.stream()
                .map(GoalBlock::new)
                .toArray(Goal[]::new);
            
            if (goals.length > 0) {
                GoalComposite compositeGoal = new GoalComposite(goals);
                customGoalProcess.setGoalAndPath(compositeGoal);
            }
        }
    }
    
    private void updateCustomGoal(IBaritone baritone) {
        List<BlockPos> positions = collectOrePositions();
        
        if (positions.isEmpty()) return;
        
        //? if >=1.21.10 {
        Vec3d playerPos = mc.player.getEntityPos();
        //?} else
        /*Vec3d playerPos = mc.player.getPos();
        */
        positions.sort((pos1, pos2) -> {
            double dist1 = playerPos.squaredDistanceTo(Vec3d.ofCenter(pos1));
            double dist2 = playerPos.squaredDistanceTo(Vec3d.ofCenter(pos2));
            return Double.compare(dist1, dist2);
        });
        
        ICustomGoalProcess customGoalProcess = baritone.getCustomGoalProcess();
        if (customGoalProcess != null) {
            Goal[] goals = positions.stream()
                .map(GoalBlock::new)
                .toArray(Goal[]::new);
            
            if (goals.length > 0) {
                GoalComposite compositeGoal = new GoalComposite(goals);
                
                boolean nearMinedPosition = false;
                for (BlockPos goalPos : positions) {
                    double distance = playerPos.distanceTo(Vec3d.ofCenter(goalPos));
                    if (distance < 2.0 && mc.world.getBlockState(goalPos).isAir()) {
                        nearMinedPosition = true;
                        break;
                    }
                }
                
                customGoalProcess.setGoal(compositeGoal);
                
                if (!customGoalProcess.isActive() || nearMinedPosition) {
                    customGoalProcess.path();
                }
            }
        }
    }
    
    private List<BlockPos> collectOrePositions() {
        List<BlockPos> positions = new ArrayList<>();
        ChunkPos chunkPos = mc.player.getChunkPos();
        int rangeVal = 4;
        for (int range = 0; range <= rangeVal; range++) {
            for (int x = -range + chunkPos.x; x <= range + chunkPos.x; x++) {
                positions.addAll(getOrePositionsFromChunk(x, chunkPos.z + range - rangeVal));
            }
            for (int x = -range + 1 + chunkPos.x; x < range + chunkPos.x; x++) {
                positions.addAll(getOrePositionsFromChunk(x, chunkPos.z - range + rangeVal + 1));
            }
        }
        
        List<BlockOptionalMeta> activeOreBlocks = getActiveOreBlocks();
        Set<Block> targetBlocks = new HashSet<>();
        for (BlockOptionalMeta bom : activeOreBlocks) {
            targetBlocks.add(bom.getBlock());
        }
        
        positions.removeIf(pos -> {
            if (mc.world == null) return false;
            net.minecraft.block.BlockState state = mc.world.getBlockState(pos);
            if (state.isAir()) return true;
            net.minecraft.block.Block block = state.getBlock();
            return !targetBlocks.contains(block);
        });
        
        return positions;
    }
    
    private List<BlockPos> getOrePositionsFromChunk(int chunkX, int chunkZ) {
        List<BlockPos> positions = new ArrayList<>();
        if (oreConfig == null) return positions;
        
        long chunkKey = ChunkPos.toLong(chunkX, chunkZ);
        Map<Ore, Set<Vec3d>> chunkData = chunkRenderers.get(chunkKey);
        if (chunkData == null) return positions;
        
        for (Map.Entry<Ore, Set<Vec3d>> entry : chunkData.entrySet()) {
            Ore ore = entry.getKey();
            if (!ore.active.get()) continue;
            
            for (Vec3d pos : entry.getValue()) {
                BlockPos blockPos = new BlockPos((int) pos.x, (int) pos.y, (int) pos.z);
                positions.add(blockPos);
            }
        }
        
        return positions;
    }
    
    private List<BlockOptionalMeta> getActiveOreBlocks() {
        List<BlockOptionalMeta> blocks = new ArrayList<>();
        if (oreConfig == null) return blocks;
        
        Set<Block> activeOreBlocks = new HashSet<>();
        
        Setting<Boolean> coalSetting = Ore.oreSettings.get(0);
        Setting<Boolean> ironSetting = Ore.oreSettings.get(1);
        Setting<Boolean> goldSetting = Ore.oreSettings.get(2);
        Setting<Boolean> redstoneSetting = Ore.oreSettings.get(3);
        Setting<Boolean> diamondSetting = Ore.oreSettings.get(4);
        Setting<Boolean> lapisSetting = Ore.oreSettings.get(5);
        Setting<Boolean> copperSetting = Ore.oreSettings.get(6);
        Setting<Boolean> emeraldSetting = Ore.oreSettings.get(7);
        Setting<Boolean> quartzSetting = Ore.oreSettings.get(8);
        Setting<Boolean> debrisSetting = Ore.oreSettings.get(9);
        
        for (List<Ore> ores : oreConfig.values()) {
            for (Ore ore : ores) {
                if (ore.active.get()) {
                    if (ore.active == coalSetting) {
                        activeOreBlocks.add(Blocks.COAL_ORE);
                        activeOreBlocks.add(Blocks.DEEPSLATE_COAL_ORE);
                    } else if (ore.active == ironSetting) {
                        activeOreBlocks.add(Blocks.IRON_ORE);
                        activeOreBlocks.add(Blocks.DEEPSLATE_IRON_ORE);
                    } else if (ore.active == goldSetting) {
                        activeOreBlocks.add(Blocks.GOLD_ORE);
                        activeOreBlocks.add(Blocks.DEEPSLATE_GOLD_ORE);
                        activeOreBlocks.add(Blocks.NETHER_GOLD_ORE);
                    } else if (ore.active == diamondSetting) {
                        activeOreBlocks.add(Blocks.DIAMOND_ORE);
                        activeOreBlocks.add(Blocks.DEEPSLATE_DIAMOND_ORE);
                    } else if (ore.active == redstoneSetting) {
                        activeOreBlocks.add(Blocks.REDSTONE_ORE);
                        activeOreBlocks.add(Blocks.DEEPSLATE_REDSTONE_ORE);
                    } else if (ore.active == lapisSetting) {
                        activeOreBlocks.add(Blocks.LAPIS_ORE);
                        activeOreBlocks.add(Blocks.DEEPSLATE_LAPIS_ORE);
                    } else if (ore.active == copperSetting) {
                        activeOreBlocks.add(Blocks.COPPER_ORE);
                        activeOreBlocks.add(Blocks.DEEPSLATE_COPPER_ORE);
                    } else if (ore.active == emeraldSetting) {
                        activeOreBlocks.add(Blocks.EMERALD_ORE);
                        activeOreBlocks.add(Blocks.DEEPSLATE_EMERALD_ORE);
                    } else if (ore.active == quartzSetting) {
                        activeOreBlocks.add(Blocks.NETHER_QUARTZ_ORE);
                    } else if (ore.active == debrisSetting) {
                        activeOreBlocks.add(Blocks.ANCIENT_DEBRIS);
                    }
                }
            }
        }
        
        for (Block block : activeOreBlocks) {
            blocks.add(new BlockOptionalMeta(block));
        }
        
        return blocks;
    }
    

    @Override
    public void onActivate() {
        if (Seeds.get().getSeed() == null) {
            error("No seed found. Run .seed <seed> to set one.");
            toggle();
            return;
        }
        if (!BaritoneUtils.IS_AVAILABLE) {
            info("Baritone not detected. Requires 'baritone-api-fabric' (not 'baritone-standalone-fabric').");
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
