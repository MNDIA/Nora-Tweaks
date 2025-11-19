package me.noramibu.tweaks.modules;

import me.noramibu.tweaks.NoraTweaks;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.HoeItem;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

public class AutoFarmLand extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("The radius to search for blocks.")
        .defaultValue(3.5)
        .min(1)
        .max(10)
        .sliderMax(5)
        .build()
    );

    private final Setting<Boolean> turnGrassBlock = sgGeneral.add(new BoolSetting.Builder()
        .name("grass-block")
        .description("Whether to turn grass blocks into farm land.")
        .defaultValue(true)
        .onChanged(v -> updateTargetBlocks())
        .build()
    );

    private final Setting<Boolean> turnDirt = sgGeneral.add(new BoolSetting.Builder()
        .name("dirt")
        .description("Whether to turn dirt blocks into farm land.")
        .defaultValue(true)
        .onChanged(v -> updateTargetBlocks())
        .build()
    );

    private final Setting<Boolean> turnDirtPath = sgGeneral.add(new BoolSetting.Builder()
        .name("dirt-path")
        .description("Whether to turn dirt paths into farm land.")
        .defaultValue(true)
        .onChanged(v -> updateTargetBlocks())
        .build()
    );

    private final Setting<Boolean> turnCoarseDirt = sgGeneral.add(new BoolSetting.Builder()
        .name("coarse-dirt")
        .description("Whether to turn coarse dirt blocks into farm land.")
        .defaultValue(true)
        .onChanged(v -> updateTargetBlocks())
        .build()
    );

    private final Setting<Boolean> requireOnClick = sgGeneral.add(new BoolSetting.Builder()
        .name("require-on-click")
        .description("Requires you to be holding right click for the module to work.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("How many blocks to convert per tick. Higher values may cause lag.")
        .defaultValue(1)
        .min(1)
        .sliderMax(50)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Faces the block before interacting. Disabling this is faster but might not work on some servers.")
        .defaultValue(true)
        .build()
    );

    private final Set<Block> targetBlocks = new HashSet<>();

    public AutoFarmLand() {
        super(NoraTweaks.CATEGORY, "auto-farm-land", "Automatically creates farm land when holding a hoe.");
        updateTargetBlocks();
    }

    private void updateTargetBlocks() {
        targetBlocks.clear();
        if (turnGrassBlock.get()) targetBlocks.add(Blocks.GRASS_BLOCK);
        if (turnDirt.get()) targetBlocks.add(Blocks.DIRT);
        if (turnCoarseDirt.get()) targetBlocks.add(Blocks.COARSE_DIRT);
        if (turnDirtPath.get()) targetBlocks.add(Blocks.DIRT_PATH);
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!(mc.player.getMainHandStack().getItem() instanceof HoeItem)) {
            return;
        }

        if (requireOnClick.get() && !mc.options.useKey.isPressed()) {
            return;
        }

        if (targetBlocks.isEmpty()) return;

        final double rangeSq = range.get() * range.get();
        BlockPos.stream(mc.player.getBoundingBox().expand(range.get()))
            .map(BlockPos::toImmutable)
            .filter(bp -> targetBlocks.contains(mc.world.getBlockState(bp).getBlock()))
            .filter(bp -> {
                //? if >=1.21.10 {
                return mc.player.getEntityPos().squaredDistanceTo(Vec3d.ofCenter(bp)) <= rangeSq;
                //?} else
                /*return mc.player.getPos().squaredDistanceTo(Vec3d.ofCenter(bp)) <= rangeSq;
                */
            })
            .sorted(Comparator.comparing(bp -> {
                //? if >=1.21.10 {
                return mc.player.getEntityPos().distanceTo(Vec3d.ofCenter(bp));
                //?} else
                /*return mc.player.getPos().distanceTo(Vec3d.ofCenter(bp));
                */
            }))
            .limit(blocksPerTick.get())
            .forEach(this::turnToFarmland);
    }

    private void turnToFarmland(BlockPos blockPos) {
        Runnable action = () -> {
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, new BlockHitResult(
                Vec3d.ofCenter(blockPos), Direction.UP, blockPos, false
            ));
            mc.player.swingHand(Hand.MAIN_HAND);
        };

        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(blockPos), Rotations.getPitch(blockPos), action);
        } else {
            action.run();
        }
    }
}
