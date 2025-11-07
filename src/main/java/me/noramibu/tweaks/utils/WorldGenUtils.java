/*
 * This code partially adapted from Meteor Rejects
 * Original source: https://github.com/AntiCope/meteor-rejects/
 * Credit: Meteor Rejects contributors
 * If Meteor Rejects gets updated, adapted features will get removed.
 */
package me.noramibu.tweaks.utils;

import cubitect.Cubiomes.Pos;
import cubitect.Cubiomes;
import me.noramibu.tweaks.utils.Seeds;
import me.noramibu.tweaks.utils.Seeds.Seed;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapDecorationsComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.*;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.vehicle.ChestMinecartEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class WorldGenUtils {

    private static final Logger LOG = LogManager.getLogger();


    private static final Map<Feature, List<Class<? extends Entity>>> FEATURE_ENTITIES = new HashMap<>() {{
        put(Feature.ocean_monument, Arrays.asList(ElderGuardianEntity.class, GuardianEntity.class));
        put(Feature.nether_fortress, Arrays.asList(BlazeEntity.class, WitherSkeletonEntity.class));
        put(Feature.mansion, Collections.singletonList(EvokerEntity.class));
        put(Feature.slime_chunk, Collections.singletonList(SlimeEntity.class));
        put(Feature.bastion_remnant, Collections.singletonList(PiglinBruteEntity.class));
        put(Feature.end_city, Collections.singletonList(ShulkerEntity.class));
        put(Feature.village, Arrays.asList(VillagerEntity.class, IronGolemEntity.class));
        put(Feature.mineshaft, Collections.singletonList(ChestMinecartEntity.class));
    }};

    public enum Feature {
        buried_treasure,
        mansion,
        stronghold,
        nether_fortress,
        ocean_monument,
        bastion_remnant,
        end_city,
        village,
        mineshaft,
        slime_chunk,
        desert_pyramid
    }

    public static BlockPos locateFeature(Cubiomes.StructureType cFeature, BlockPos center) {
        Feature feature = switch (cFeature) {
            case Treasure -> Feature.buried_treasure;
            case Mansion -> Feature.mansion;
            case Stronghold -> Feature.stronghold;
            case Fortress -> Feature.nether_fortress;
            case Monument -> Feature.ocean_monument;
            case Bastion -> Feature.bastion_remnant;
            case End_City -> Feature.end_city;
            case Village -> Feature.village;
            case Mineshaft -> Feature.mineshaft;
            case Desert_Pyramid -> Feature.desert_pyramid;
            default -> null;
        };
        if (feature == null) return null;

        Seed seed = Seeds.get().getSeed();
        if (!isInDimension(getDimension(feature))) {
            return null;
        }
        if (seed != null) {
            try {
                BlockPos located = locateFeature(seed, feature, center);
                if (located != null) return located;
            } catch (Exception | Error ex) {
                LOG.error("Failed to locate feature via seed", ex);
            }
        }

        if (mc.player != null) {
            ItemStack stack = mc.player.getStackInHand(Hand.MAIN_HAND);
            if (stack.isEmpty()) {
                stack = mc.player.getStackInHand(Hand.OFF_HAND);
            }
            if (!stack.isEmpty()) {
                try {
                    BlockPos mapPos = locateFeatureMap(feature, stack);
                    if (mapPos != null) return mapPos;
                } catch (Exception | Error ex) {
                    LOG.error("Failed to locate feature via map", ex);
                }
            }
        }

        try {
            BlockPos entityPos = locateFeatureEntities(feature);
            if (entityPos != null) return entityPos;
        } catch (Exception | Error ex) {
            LOG.error("Failed to locate feature via entities", ex);
        }

        // Block-based fallback removed
        return null;
    }

    private static BlockPos locateFeatureMap(Feature feature, ItemStack stack) {
        if (!isValidMap(feature, stack)) return null;
        return getMapMarker(stack);
    }

    // Block-based locator removed

    private static BlockPos locateFeatureEntities(Feature feature) {
        List<Class<? extends Entity>> entities = FEATURE_ENTITIES.get(feature);
        if (entities == null || mc.world == null) return null;

        for (Entity entity : mc.world.getEntities()) {
            for (Class<? extends Entity> clazz : entities) {
                if (clazz.isInstance(entity)) {
                    return entity.getBlockPos();
                }
            }
        }
        return null;
    }

    private static BlockPos locateFeature(Seed seed, Feature feature, BlockPos center) {
        Cubiomes.StructureType cType = switch (feature) {
            case buried_treasure -> Cubiomes.StructureType.Treasure;
            case mansion -> Cubiomes.StructureType.Mansion;
            case stronghold -> Cubiomes.StructureType.Stronghold;
            case nether_fortress -> Cubiomes.StructureType.Fortress;
            case ocean_monument -> Cubiomes.StructureType.Monument;
            case bastion_remnant -> Cubiomes.StructureType.Bastion;
            case end_city -> Cubiomes.StructureType.End_City;
            case village -> Cubiomes.StructureType.Village;
            case mineshaft -> Cubiomes.StructureType.Mineshaft;
            case slime_chunk -> null;
            case desert_pyramid -> Cubiomes.StructureType.Desert_Pyramid;
        };
        if (cType == null) return null;
        Pos pos = Cubiomes.GetNearestStructure(cType, center.getX(), center.getZ(), seed.seed, seed.version);
        if (pos == null) return null;
        return new BlockPos(pos.x, 0, pos.z);
    }

    private static boolean isInDimension(meteordevelopment.meteorclient.utils.world.Dimension dimension) {
        return PlayerUtils.getDimension() == dimension;
    }

    private static meteordevelopment.meteorclient.utils.world.Dimension getDimension(Feature feature) {
        return switch (feature) {
            case nether_fortress, bastion_remnant -> meteordevelopment.meteorclient.utils.world.Dimension.Nether;
            case end_city -> meteordevelopment.meteorclient.utils.world.Dimension.End;
            default -> meteordevelopment.meteorclient.utils.world.Dimension.Overworld;
        };
    }

    private static boolean isValidMap(Feature feature, ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (!stack.getComponents().contains(DataComponentTypes.MAP_DECORATIONS)) return false;
        MapDecorationsComponent component = stack.get(DataComponentTypes.MAP_DECORATIONS);
        if (component == null || component.decorations().isEmpty()) return false;
        String name = component.toString();
        if (!name.contains("translate")) return false;
        return switch (feature) {
            case buried_treasure -> name.contains("filled_map.buried_treasure");
            case ocean_monument -> name.contains("filled_map.monument");
            case mansion -> name.contains("filled_map.mansion");
            default -> false;
        };
    }

    private static BlockPos getMapMarker(ItemStack stack) {
        if (!stack.getComponents().contains(DataComponentTypes.MAP_DECORATIONS)) return null;
        MapDecorationsComponent component = stack.get(DataComponentTypes.MAP_DECORATIONS);
        if (component == null || component.decorations().isEmpty()) return null;
        MapDecorationsComponent.Decoration decoration = component.decorations().get(0);
        return new BlockPos((int) decoration.x(), 0, (int) decoration.z());
    }

    // sq(int) unused after SeedFinding removal
}

