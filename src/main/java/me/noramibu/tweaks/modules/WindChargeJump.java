package me.noramibu.tweaks.modules;

import me.noramibu.tweaks.NoraTweaks;
import meteordevelopment.meteorclient.events.entity.EntityAddedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.projectile.WindChargeEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.util.math.Vec3d;

public class WindChargeJump extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> pitchThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("pitch-threshold")
        .description("Minimum downward pitch angle to trigger auto-jump (0 = horizontal, 90 = straight down).")
        .defaultValue(50.0)
        .min(0)
        .max(90)
        .sliderMax(90)
        .build()
    );

    private final Setting<Boolean> requireLookingDown = sgGeneral.add(new BoolSetting.Builder()
        .name("require-looking-down")
        .description("Only auto-jump when looking downward when throwing the wind charge.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> jumpForce = sgGeneral.add(new DoubleSetting.Builder()
        .name("jump-force")
        .description("Additional upward velocity to apply (0.42 is normal jump strength).")
        .defaultValue(0.42)
        .min(0.1)
        .max(2.0)
        .sliderMax(1.0)
        .build()
    );

    private final Setting<Boolean> onlyOnGround = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-ground")
        .description("Only auto-jump when player is on the ground.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> jumpDelay = sgGeneral.add(new IntSetting.Builder()
        .name("jump-delay")
        .description("Delay in ticks before jumping (20 ticks = 1 second).")
        .defaultValue(2)
        .min(0)
        .max(20)
        .sliderMax(10)
        .build()
    );

    private boolean windChargeThrown = false;
    private int jumpTick = 0;

    public WindChargeJump() {
        super(NoraTweaks.CATEGORY, "wind-charge-jump", "Automatically jumps when you throw a wind charge underneath yourself.");
    }

    @Override
    public void onActivate() {
        windChargeThrown = false;
        jumpTick = 0;
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (mc.player == null) return;

        if (event.packet instanceof PlayerInteractItemC2SPacket packet) {
            // Check if player is using a wind charge
            if (mc.player.getMainHandStack().getItem() == Items.WIND_CHARGE || 
                mc.player.getOffHandStack().getItem() == Items.WIND_CHARGE) {
                
                if (shouldTriggerJump()) {
                    windChargeThrown = true;
                    jumpTick = jumpDelay.get();
                }
            }
        }
    }

    @EventHandler
    private void onEntityAdded(EntityAddedEvent event) {
        if (mc.player == null || !windChargeThrown) return;

        // Check if a wind charge entity was spawned near the player
        if (event.entity instanceof WindChargeEntity windCharge) {
            Vec3d playerPos = mc.player.getPos();
            Vec3d windChargePos = windCharge.getPos();
            
            // Check if the wind charge is close to the player (indicating they threw it)
            if (playerPos.distanceTo(windChargePos) < 3.0) {
                // The wind charge will create a wind burst shortly, so we schedule the jump
                if (jumpTick <= 0) {
                    jumpTick = jumpDelay.get();
                }
            }
        }
    }

    @EventHandler
    private void onTick(meteordevelopment.meteorclient.events.world.TickEvent.Pre event) {
        if (mc.player == null) return;

        if (windChargeThrown && jumpTick > 0) {
            jumpTick--;
            
            if (jumpTick == 0) {
                performJump();
                windChargeThrown = false;
            }
        }
    }

    private boolean shouldTriggerJump() {
        if (mc.player == null) return false;

        // Check if player is on ground (if required)
        if (onlyOnGround.get() && !mc.player.isOnGround()) {
            return false;
        }

        // Check if player is looking down (if required)
        if (requireLookingDown.get()) {
            float pitch = mc.player.getPitch();
            if (pitch < pitchThreshold.get()) {
                return false;
            }
        }

        return true;
    }

    private void performJump() {
        if (mc.player == null) return;

        // Add upward velocity to make the player jump
        Vec3d velocity = mc.player.getVelocity();
        mc.player.setVelocity(velocity.x, jumpForce.get(), velocity.z);
    }
} 