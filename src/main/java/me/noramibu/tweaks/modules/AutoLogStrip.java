package me.noramibu.tweaks.modules;

import me.noramibu.tweaks.NoraTweaks;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.TickEvent.Pre;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class AutoLogStrip extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgOperation = settings.createGroup("Operation");

    private final Setting<Boolean> autoRotate = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-rotate")
        .description("Automatically rotate to face target blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swingHand = sgGeneral.add(new BoolSetting.Builder()
        .name("swing-hand")
        .description("Swing hand when performing actions.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Show operation status messages.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> maxRange = sgOperation.add(new DoubleSetting.Builder()
        .name("max-range")
        .description("Maximum range to operate on logs.")
        .defaultValue(4.5)
        .min(1.0)
        .sliderRange(1.0, 6.0)
        .build()
    );

    private final Setting<Integer> stripDelay = sgOperation.add(new IntSetting.Builder()
        .name("strip-delay")
        .description("Delay in ticks before stripping.")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> breakDelay = sgOperation.add(new IntSetting.Builder()
        .name("break-delay")
        .description("Delay in ticks before breaking.")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> placeDelay = sgOperation.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Delay in ticks before placing next log.")
        .defaultValue(2)
        .min(1)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Boolean> autoPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-place")
        .description("Automatically place logs after breaking stripped logs.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> refillWithAnyLog = sgGeneral.add(new BoolSetting.Builder()
        .name("refill-with-any-log")
        .description("Refill off-hand with any log type. If false, only uses the same type as first log.")
        .defaultValue(true)
        .visible(autoPlace::get)
        .build()
    );

    private enum State { IDLE, WAITING_TO_STRIP, STRIPPING, WAITING_TO_BREAK, BREAKING, WAITING_TO_PLACE }

    private State state = State.IDLE;
    private BlockPos targetPos = null;
    private int timer = 0;
    private Item firstLogType = null;
    private int breakProgress = 0;

    public AutoLogStrip() {
        super(NoraTweaks.CATEGORY, "auto-log-strip", "Automatically places, strips, and breaks logs for efficient processing.");
    }

    @Override
    public void onActivate() {
        reset();
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    private void reset() {
        state = State.IDLE;
        targetPos = null;
        timer = 0;
        firstLogType = null;
        breakProgress = 0;
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (mc.player == null || mc.world == null) return;
        if (state != State.IDLE && state != State.WAITING_TO_PLACE) return;

        if (event.oldState.isAir() && isUnstrippedLog(event.newState)) {
            if (!isInRange(event.pos)) return;

            if (firstLogType == null) {
                firstLogType = event.newState.getBlock().asItem();
                if (chatFeedback.get()) info("First log type: " + getBlockName(event.newState.getBlock()));
            }

            targetPos = event.pos.toImmutable();
            state = State.WAITING_TO_STRIP;
            timer = stripDelay.get();
            if (chatFeedback.get()) info("Log detected, stripping in " + timer + " ticks");
        }
    }

    @EventHandler
    private void onTick(Pre event) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (timer-- > 0) return;

        switch (state) {
            case IDLE -> {}
            case WAITING_TO_STRIP -> handleWaitingToStrip();
            case STRIPPING -> handleStripping();
            case WAITING_TO_BREAK -> handleWaitingToBreak();
            case BREAKING -> handleBreaking();
            case WAITING_TO_PLACE -> handleWaitingToPlace();
        }
    }

    private void handleWaitingToStrip() {
        if (!validateTarget()) return;
        BlockState blockState = mc.world.getBlockState(targetPos);

        if (isUnstrippedLog(blockState)) {
            state = State.STRIPPING;
            doStrip();
        } else if (isStrippedLog(blockState)) {
            state = State.WAITING_TO_BREAK;
            timer = breakDelay.get();
        } else {
            reset();
        }
    }

    private void handleStripping() {
        if (targetPos == null) { reset(); return; }
        BlockState blockState = mc.world.getBlockState(targetPos);

        if (isStrippedLog(blockState)) {
            if (chatFeedback.get()) info("Log stripped");
            state = State.WAITING_TO_BREAK;
            timer = breakDelay.get();
        } else if (isUnstrippedLog(blockState)) {
            doStrip();
        } else {
            reset();
        }
    }

    private void handleWaitingToBreak() {
        if (!validateTarget()) return;
        BlockState blockState = mc.world.getBlockState(targetPos);

        if (isStrippedLog(blockState)) {
            state = State.BREAKING;
            breakProgress = 0;
            doBreak();
        } else {
            reset();
        }
    }

    private void handleBreaking() {
        if (targetPos == null) { reset(); return; }
        BlockState blockState = mc.world.getBlockState(targetPos);

        if (blockState.isAir()) {
            if (chatFeedback.get()) info("Log broken");
            if (autoPlace.get()) {
                state = State.WAITING_TO_PLACE;
                timer = placeDelay.get();
            } else {
                reset();
            }
        } else if (isStrippedLog(blockState)) {
            if (++breakProgress > 20) {
                if (chatFeedback.get()) warning("Breaking slow, retrying...");
                breakProgress = 0;
            }
            doBreak();
        } else {
            reset();
        }
    }

    private void handleWaitingToPlace() {
        if (targetPos == null) { reset(); return; }
        BlockState blockState = mc.world.getBlockState(targetPos);

        if (!blockState.isAir()) {
            if (isUnstrippedLog(blockState)) {
                state = State.WAITING_TO_STRIP;
                timer = stripDelay.get();
            } else {
                reset();
            }
            return;
        }

        if (doPlace()) {
            if (chatFeedback.get()) info("Placed new log");
        } else {
            if (chatFeedback.get()) warning("Failed to place log");
            reset();
        }
    }

    private boolean validateTarget() {
        if (targetPos == null || !isInRange(targetPos)) {
            reset();
            return false;
        }
        return true;
    }

    private void doStrip() {
        FindItemResult axe = InvUtils.findInHotbar(s -> s.getItem() instanceof AxeItem);
        if (!axe.found()) {
            if (chatFeedback.get()) error("No axe found!");
            reset();
            return;
        }

        doAction(() -> {
            InvUtils.swap(axe.slot(), true);
            mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                new BlockHitResult(Vec3d.ofCenter(targetPos), Direction.UP, targetPos, false));
            if (swingHand.get()) mc.player.swingHand(Hand.MAIN_HAND);
            InvUtils.swapBack();
        });
    }

    private void doBreak() {
        FindItemResult axe = InvUtils.findInHotbar(s -> s.getItem() instanceof AxeItem);
        if (!axe.found()) {
            if (chatFeedback.get()) error("No axe found!");
            reset();
            return;
        }

        doAction(() -> {
            InvUtils.swap(axe.slot(), false);
            mc.interactionManager.updateBlockBreakingProgress(targetPos, Direction.UP);
            if (swingHand.get()) mc.player.swingHand(Hand.MAIN_HAND);
        });
    }

    private boolean doPlace() {
        ItemStack offHand = mc.player.getOffHandStack();
        if (!isLogItem(offHand) && !refillOffHand()) return false;

        BlockPos placeOn = findPlaceablePosition();
        if (placeOn == null) return false;

        int dx = targetPos.getX() - placeOn.getX();
        int dy = targetPos.getY() - placeOn.getY();
        int dz = targetPos.getZ() - placeOn.getZ();
        Direction placeDir = Direction.UP;
        if (dy > 0) placeDir = Direction.UP;
        else if (dy < 0) placeDir = Direction.DOWN;
        else if (dx > 0) placeDir = Direction.EAST;
        else if (dx < 0) placeDir = Direction.WEST;
        else if (dz > 0) placeDir = Direction.SOUTH;
        else if (dz < 0) placeDir = Direction.NORTH;

        Direction finalDir = placeDir;
        doAction(() -> {
            mc.interactionManager.interactBlock(mc.player, Hand.OFF_HAND,
                new BlockHitResult(Vec3d.ofCenter(placeOn).add(0, 0.5, 0), finalDir, placeOn, false));
            if (swingHand.get()) mc.player.swingHand(Hand.OFF_HAND);
        });

        return true;
    }

    private void doAction(Runnable action) {
        if (autoRotate.get()) {
            Rotations.rotate(Rotations.getYaw(targetPos), Rotations.getPitch(targetPos), -100, action);
        } else {
            action.run();
        }
    }

    private BlockPos findPlaceablePosition() {
        BlockPos below = targetPos.down();
        if (!mc.world.getBlockState(below).isAir()) return below;

        for (Direction dir : Direction.values()) {
            BlockPos adjacent = targetPos.offset(dir);
            if (!mc.world.getBlockState(adjacent).isAir()) return adjacent;
        }
        return null;
    }

    private boolean refillOffHand() {
        FindItemResult result;

        if (refillWithAnyLog.get()) {
            result = InvUtils.find(s -> isLogItem(s) && InvUtils.find(s.getItem()).slot() != 45);
        } else if (firstLogType != null) {
            result = InvUtils.find(s -> s.getItem() == firstLogType && InvUtils.find(s.getItem()).slot() != 45);
        } else {
            return false;
        }

        if (!result.found() || result.slot() == 45) return false;

        InvUtils.move().from(result.slot()).toOffhand();
        if (chatFeedback.get()) info("Refilled off-hand");
        return true;
    }

    private boolean isInRange(BlockPos pos) {
        return mc.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
            <= maxRange.get() * maxRange.get();
    }

    private boolean isUnstrippedLog(BlockState state) {
        return state.isIn(BlockTags.LOGS) && !isStripped(state.getBlock());
    }

    private boolean isStrippedLog(BlockState state) {
        return state.isIn(BlockTags.LOGS) && isStripped(state.getBlock());
    }

    private boolean isStripped(Block block) {
        return Registries.BLOCK.getId(block).getPath().contains("stripped");
    }

    private boolean isLogItem(ItemStack stack) {
        return stack.isIn(ItemTags.LOGS) && !stack.isEmpty();
    }

    private String getBlockName(Block block) {
        return Registries.BLOCK.getId(block).getPath().replace("_", " ").toUpperCase();
    }
}
