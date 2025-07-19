package me.noramibu.tweaks.modules;

import me.noramibu.tweaks.NoraTweaks;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

public class WindChargeJump extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> pitchThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("pitch-threshold")
        .description("Minimum downward pitch angle when throwing to trigger auto-jump (0 = horizontal, 90 = straight down).")
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

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Send a chat message when wind charge jump is triggered.")
        .defaultValue(true)
        .build()
    );

    private int jumpTicksRemaining = 0;

    public WindChargeJump() {
        super(NoraTweaks.CATEGORY, "wind-charge-jump", "Automatically jumps when you throw a wind charge underneath yourself.");
    }

    @Override
    public void onActivate() {
        jumpTicksRemaining = 0;
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (mc.player == null) return;

        // Don't interfere if Mace Combo is using wind charges
        if (MaceCombo.isUsingWindCharge) return;

        if (event.packet instanceof PlayerInteractItemC2SPacket) {
            // Check if player is using a wind charge
            if (mc.player.getMainHandStack().getItem() == Items.WIND_CHARGE || 
                mc.player.getOffHandStack().getItem() == Items.WIND_CHARGE) {
                
                if (shouldTriggerJump()) {
                    jumpTicksRemaining = jumpDelay.get();
                    
                    if (chatFeedback.get()) {
                        mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §7Wind Charge Jump triggered!"), false);
                    }
                }
            }
        }
    }

    @EventHandler
    private void onTick(meteordevelopment.meteorclient.events.world.TickEvent.Pre event) {
        if (mc.player == null) return;

        if (jumpTicksRemaining > 0) {
            jumpTicksRemaining--;
            
            if (jumpTicksRemaining == 0) {
                performJump();
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