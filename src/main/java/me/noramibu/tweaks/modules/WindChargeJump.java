package me.noramibu.tweaks.modules;

import me.noramibu.tweaks.NoraTweaks;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;

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

    private final Setting<Boolean> onlyOnGround = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-ground")
        .description("Only auto-jump when player is on the ground.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> jumpDelay = sgGeneral.add(new IntSetting.Builder()
        .name("jump-delay")
        .description("Delay in ticks before jumping.")
        .defaultValue(2)
        .min(0)
        .sliderRange(0, 10)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Send a chat message when wind charge jump is triggered.")
        .defaultValue(false)
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
        if (MaceCombo.isUsingWindCharge) return;

        if (event.packet instanceof PlayerInteractItemC2SPacket) {
            if (mc.player.getMainHandStack().getItem() == Items.WIND_CHARGE ||
                mc.player.getOffHandStack().getItem() == Items.WIND_CHARGE) {

                if (shouldTriggerJump()) {
                    jumpTicksRemaining = jumpDelay.get();
                    if (chatFeedback.get()) info("Wind Charge Jump triggered!");
                }
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        if (jumpTicksRemaining > 0) {
            jumpTicksRemaining--;
            if (jumpTicksRemaining == 0) {
                mc.player.jump();
            }
        }
    }

    private boolean shouldTriggerJump() {
        if (mc.player == null) return false;
        if (onlyOnGround.get() && !mc.player.isOnGround()) return false;
        if (requireLookingDown.get() && mc.player.getPitch() < pitchThreshold.get()) return false;
        return true;
    }
}
