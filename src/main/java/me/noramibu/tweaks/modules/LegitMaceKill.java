package me.noramibu.tweaks.modules;

/**
 * Original code written by @etianl https://github.com/etianl/Trouser-Streak/blob/main/src/main/java/pwn/noobs/trouserstreak/modules/MaceKill.java 
 */

import me.noramibu.tweaks.NoraTweaks;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.mixininterface.IPlayerInteractEntityC2SPacket;
import meteordevelopment.meteorclient.mixininterface.IPlayerMoveC2SPacket;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.VehicleMoveC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class LegitMaceKill extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgInfo = settings.createGroup("Note: Disable \"Smash Attack\" in the Criticals module to make this work properly.");
    private final Setting<Boolean> preventDeath = sgGeneral.add(new BoolSetting.Builder()
            .name("Prevent Fall damage")
            .description("Attempts to prevent fall damage even on packet hiccups.")
            .defaultValue(true)
            .build());
    private final Setting<Double> fallMultiplier = sgGeneral.add(new DoubleSetting.Builder()
            .name("Fall Height Multiplier")
            .description("Multiplies your current fall distance by this amount (e.g., 1.5x means 10 blocks becomes 15 blocks)")
            .defaultValue(1.5)
            .min(1.0)
            .max(500.0)
            .sliderMin(1.0)
            .sliderMax(50.0)
            .build());
    private final Setting<Boolean> capAt170Blocks = sgGeneral.add(new BoolSetting.Builder()
            .name("Cap at 170 blocks")
            .description("Limits amplified fall height to 170 blocks to reduce desync and anti-cheat issues. Disable to allow full multiplier.")
            .defaultValue(true)
            .build());
    private final Setting<Double> minFallHeight = sgGeneral.add(new DoubleSetting.Builder()
            .name("Minimum Fall Height")
            .description("Only activates if you fall from at least this height")
            .defaultValue(2.0)
            .min(0.5)
            .max(20.0)
            .sliderMax(10.0)
            .build());

    private final Setting<Boolean> packetDisable = sgGeneral.add(new BoolSetting.Builder()
            .name("Disable When Blocked")
            .description("Does not send movement packets if the attack was blocked. (prevents death)")
            .defaultValue(true)
            .build());

    private final Setting<Integer> damageChance = sgGeneral.add(new IntSetting.Builder()
            .name("Damage Chance")
            .description("Percentage chance for damage amplification to trigger (1-100%).")
            .defaultValue(100)
            .min(1)
            .max(100)
            .sliderMax(100)
            .build());

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
            .name("Chat Feedback")
            .description("Send a chat message when mace damage amplification is triggered.")
            .defaultValue(true)
            .build());

    public LegitMaceKill() {
        super(NoraTweaks.CATEGORY, "legit-mace-kill", "Amplifies mace damage based on fall distance. Only works when falling from minimum height.");
    }

    private Vec3d previouspos;

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (mc.player == null) return;
        if (mc.player.getMainHandStack().getItem() != Items.MACE) return;
        if (!(event.packet instanceof IPlayerInteractEntityC2SPacket packet)) return;
        // Check if this is an attack packet by checking the type
        if (packet.meteor$getEntity() == null) return;
        
        // Only proceed if the target is a LivingEntity
        if (!(packet.meteor$getEntity() instanceof LivingEntity targetEntity)) return;
        
        if (packetDisable.get() && (targetEntity.isBlocking() || targetEntity.isInvulnerable() || targetEntity.isInCreativeMode()))
            return;

        previouspos = mc.player.getEntityPos();
        
        // Don't activate if fall distance is below minimum height threshold
        if (mc.player.fallDistance < minFallHeight.get()) return;
        
        // Check damage chance
        if (Math.random() * 100 > damageChance.get()) return;
        
        int blocks = getMaxHeightAbovePlayer();
        
        // Send chat feedback if enabled
        if (chatFeedback.get()) {
            double originalFall = mc.player.fallDistance;
            double amplifiedFall = originalFall * fallMultiplier.get();
            mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §7Mace Kill triggered! Fall: " + String.format("%.1f", originalFall) + " → " + String.format("%.1f", amplifiedFall) + " blocks"), false);
        }
        
        int packetsRequired = (int) Math.ceil(Math.abs(blocks / 10.0));
        if (packetsRequired > 20) packetsRequired = 1;

        BlockPos isopenair1 = mc.player.getBlockPos().add(0, blocks, 0);
        BlockPos isopenair2 = mc.player.getBlockPos().add(0, blocks + 1, 0);
        if (!isSafeBlock(isopenair1) || !isSafeBlock(isopenair2)) return;

        if (blocks <= 22) {
            if (mc.player.hasVehicle()) {
                for (int i = 0; i < 4; i++) {
                    mc.player.networkHandler.sendPacket(VehicleMoveC2SPacket.fromVehicle(mc.player.getVehicle()));
                }
                double maxHeight = Math.min(mc.player.getVehicle().getY() + 22, mc.player.getVehicle().getY() + blocks);
                doVehicleTeleports(maxHeight, blocks);
            } else {
                for (int i = 0; i < 4; i++) {
                    mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(false, mc.player.horizontalCollision));
                }
                double heightY = Math.min(mc.player.getY() + 22, mc.player.getY() + blocks);
                doPlayerTeleports(heightY);
            }
        } else {
            if (mc.player.hasVehicle()) {
                for (int packetNumber = 0; packetNumber < (packetsRequired - 1); packetNumber++) {
                    mc.player.networkHandler.sendPacket(VehicleMoveC2SPacket.fromVehicle(mc.player.getVehicle()));
                }
                double maxHeight = mc.player.getVehicle().getY() + blocks;
                doVehicleTeleports(maxHeight, blocks);
            } else {
                for (int i = 0; i < packetsRequired - 1; i++) {
                    mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.OnGroundOnly(false, mc.player.horizontalCollision));
                }
                double heightY = mc.player.getY() + blocks;
                doPlayerTeleports(heightY);
            }
        }
    }
    private void doPlayerTeleports(double height) {
        PlayerMoveC2SPacket movepacket = new PlayerMoveC2SPacket.PositionAndOnGround(
                mc.player.getX(), height, mc.player.getZ(), false, mc.player.horizontalCollision);
        PlayerMoveC2SPacket homepacket = new PlayerMoveC2SPacket.PositionAndOnGround(
                previouspos.getX(), previouspos.getY(), previouspos.getZ(),
                false, mc.player.horizontalCollision);
        if (preventDeath.get()) {
            homepacket = new PlayerMoveC2SPacket.PositionAndOnGround(
                    previouspos.getX(), previouspos.getY() + 0.25, previouspos.getZ(),
                    false, mc.player.horizontalCollision);
        }
        ((IPlayerMoveC2SPacket) homepacket).meteor$setTag(1337);
        ((IPlayerMoveC2SPacket) movepacket).meteor$setTag(1337);
        mc.player.networkHandler.sendPacket(movepacket);
        mc.player.networkHandler.sendPacket(homepacket);
        if (preventDeath.get()) {
            mc.player.setVelocity(mc.player.getVelocity().x, 0.1, mc.player.getVelocity().z);
            mc.player.fallDistance = 0;
        }
    }
    private void doVehicleTeleports(double height, int blocks) {
        mc.player.getVehicle().setPosition(mc.player.getVehicle().getX(), height + blocks, mc.player.getVehicle().getZ());
        mc.player.networkHandler.sendPacket(VehicleMoveC2SPacket.fromVehicle(mc.player.getVehicle()));
        mc.player.getVehicle().setPosition(previouspos);
        mc.player.networkHandler.sendPacket(VehicleMoveC2SPacket.fromVehicle(mc.player.getVehicle()));
    }
    private int getMaxHeightAbovePlayer() {
        BlockPos playerPos = mc.player.getBlockPos();
        
        // Use multiplier mode based on current fall distance
        double currentFallDistance = mc.player.fallDistance;
        
        // Only activate if falling from at least the minimum height
        if (currentFallDistance < minFallHeight.get()) {
            return 0; // Don't activate if not falling from minimum height
        }
        
        int multipliedHeight = (int) (currentFallDistance * fallMultiplier.get());
        if (capAt170Blocks.get()) multipliedHeight = Math.min(multipliedHeight, 170);
        
        // Check if we can safely teleport to this height
        int targetHeight = playerPos.getY() + multipliedHeight;
        for (int i = targetHeight; i > playerPos.getY(); i--) {
            BlockPos up1 = new BlockPos(playerPos.getX(), i, playerPos.getZ());
            BlockPos up2 = up1.up(1);
            if (isSafeBlock(up1) && isSafeBlock(up2)) return i - playerPos.getY();
        }
        
        // If no safe position found, return 0 (don't activate)
        return 0;
    }

    private boolean isSafeBlock(BlockPos pos) {
        return mc.world.getBlockState(pos).isReplaceable()
                && mc.world.getFluidState(pos).isEmpty()
                && !mc.world.getBlockState(pos).isOf(Blocks.POWDER_SNOW);
    }
}