package me.noramibu.tweaks.modules;

import me.noramibu.tweaks.NoraTweaks;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IPlayerInteractEntityC2SPacket;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

public class MaceCombo extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgCombat = settings.createGroup("Combat");
    
    // Static flag to prevent Wind Charge Jump interference
    public static boolean isUsingWindCharge = false;

    private final Setting<Double> attackRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("attack-range")
        .description("Maximum range to auto-attack target.")
        .defaultValue(3.5)
        .min(2.0)
        .max(6.0)
        .sliderMax(5.0)
        .build()
    );

    private final Setting<Double> targetRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("target-range")
        .description("Maximum range to maintain target tracking.")
        .defaultValue(8.0)
        .min(5.0)
        .max(15.0)
        .sliderMax(12.0)
        .build()
    );

    private final Setting<Boolean> autoRotate = sgCombat.add(new BoolSetting.Builder()
        .name("auto-rotate")
        .description("Automatically rotate to face target.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgCombat.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Show combo status messages.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxComboTime = sgCombat.add(new IntSetting.Builder()
        .name("max-combo-time")
        .description("Maximum combo duration in seconds.")
        .defaultValue(30)
        .min(3)
        .max(60)
        .sliderMax(45)
        .build()
    );

    private final Setting<Boolean> lockFirstMace = sgCombat.add(new BoolSetting.Builder()
        .name("lock-first-mace")
        .description("Lock to the first mace found, preventing manual switching during combo.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> instantWindCharge = sgCombat.add(new BoolSetting.Builder()
        .name("instant-wind-charge")
        .description("Use wind charge immediately when landing without delay.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> windChargeDelay = sgCombat.add(new IntSetting.Builder()
        .name("wind-charge-delay")
        .description("Delay in ticks before jumping after wind charge (20 ticks = 1 second).")
        .defaultValue(1)
        .min(0)
        .max(10)
        .sliderMax(5)
        .visible(() -> !instantWindCharge.get())
        .build()
    );

    private final Setting<Boolean> optimizePitch = sgCombat.add(new BoolSetting.Builder()
        .name("optimize-pitch")
        .description("Automatically optimize pitch angle for maximum jump height.")
        .defaultValue(true)
        .build()
    );

    // State
    private LivingEntity target = null;
    private boolean comboActive = false;
    private int comboTicks = 0;
    private int hits = 0;
    private int attackCooldown = 0;
    private boolean hasLaunched = false;
    private boolean awaitingGroundCombo = false;
    private boolean lastOnGround = false;
    private int windChargeJumpTicks = 0;
    private int savedMaceSlot = -1;
    private int windChargeUseTicks = 0;
    private boolean awaitingWindChargeUse = false;
    private int firstUsedMaceSlot = -1;

    public MaceCombo() {
        super(NoraTweaks.CATEGORY, "mace-combo", "Auto-attacks targets and chains mace combos with wind charges.");
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
        target = null;
        comboActive = false;
        comboTicks = 0;
        hits = 0;
        attackCooldown = 0;
        hasLaunched = false;
        awaitingGroundCombo = false;
        lastOnGround = false;
        windChargeJumpTicks = 0;
        savedMaceSlot = -1;
        windChargeUseTicks = 0;
        awaitingWindChargeUse = false;
        firstUsedMaceSlot = -1;
    }

    @EventHandler
    private void onAttack(PacketEvent.Send event) {
        if (mc.player == null || mc.world == null) return;
        if (!(event.packet instanceof IPlayerInteractEntityC2SPacket packet)) return;
        if (mc.player.getMainHandStack().getItem() != Items.MACE) return;

        Entity entity = packet.meteor$getEntity();
        if (!(entity instanceof LivingEntity livingTarget)) return;

        // Start combo on first manual attack
        if (!comboActive && mc.player.fallDistance >= 1.5) {
            // Record the mace slot that was used to start the combo
            firstUsedMaceSlot = mc.player.getInventory().getSelectedSlot();
            startCombo(livingTarget);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Handle wind charge jump timer
        if (windChargeJumpTicks > 0) {
            windChargeJumpTicks--;
            if (windChargeJumpTicks == 0) {
                performWindChargeJump();
            }
        }

        // Handle wind charge use timer
        if (windChargeUseTicks > 0) {
            windChargeUseTicks--;
            if (windChargeUseTicks == 0) {
                useWindCharge();
            }
        }

        if (!comboActive) return;

        comboTicks++;
        if (attackCooldown > 0) attackCooldown--;

        // Check timeout
        if (comboTicks > maxComboTime.get() * 20) {
            endCombo("Combo timed out!");
            return;
        }

        // Check target validity using horizontal distance
        if (target == null || target.isDead() || target.isRemoved()) {
            endCombo("Target lost!");
            return;
        }
        double horizontalDistance = Math.sqrt(
            Math.pow(mc.player.getX() - target.getX(), 2) + 
            Math.pow(mc.player.getZ() - target.getZ(), 2)
        );
        if (horizontalDistance > targetRange.get()) {
            endCombo("Target too far!");
            return;
        }

        // Check if we have mace - only switch when needed for combat
        if (lockFirstMace.get() && mc.player.getMainHandStack().getItem() != Items.MACE && !hasAnyMaceInHand()) {
            switchToMace();
        }

        // Track if player has been launched (high Y velocity or high fall distance)
        if (mc.player.getVelocity().y > 0.5 || mc.player.fallDistance > 3.0) {
            hasLaunched = true;
        }

        // Auto-attack when falling and close to target (with cooldown)
        if (!mc.player.isOnGround() && mc.player.fallDistance >= 1.5 && attackCooldown == 0 && hasLaunched) {
            double distanceToTarget = mc.player.distanceTo(target);
            if (distanceToTarget <= attackRange.get()) {
                // Ensure we have a mace for attack if lock is enabled
                if (lockFirstMace.get() && mc.player.getMainHandStack().getItem() != Items.MACE && !hasAnyMaceInHand()) {
                    switchToMace();
                }
                
                if (autoRotate.get()) rotateToTarget();
                attackTarget();
                attackCooldown = 20; // 1 second cooldown
                hasLaunched = false;
                // Prepare to wind charge/jump on landing
                awaitingGroundCombo = true;
            }
        }

        // Ground-based wind charge usage when target is nearby
        if (mc.player.isOnGround() && attackCooldown == 0 && !awaitingGroundCombo && !awaitingWindChargeUse) {
            double distanceToTarget = mc.player.distanceTo(target);
            if (distanceToTarget <= attackRange.get() && distanceToTarget > 2.0) { // Close but not too close
                // Check if we have wind charges and target is in range
                if (!isUsingWindCharge && hasWindCharges()) {
                    int windChargeSlot = getWindChargeSlot();
                    if (windChargeSlot != -1) {
                        savedMaceSlot = mc.player.getInventory().getSelectedSlot();
                        mc.player.getInventory().setSelectedSlot(windChargeSlot);
                        isUsingWindCharge = true;
                        awaitingWindChargeUse = true;
                        
                        // Set timer for wind charge use (2 ticks = 0.1 seconds)
                        windChargeUseTicks = 2;
                        
                        if (chatFeedback.get()) {
                            mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §bSwitched to wind charge (slot " + windChargeSlot + ")"), false);
                        }
                        
                        attackCooldown = 30; // Longer cooldown for ground-based usage
                    }
                }
            }
        }

        // Detect ground contact for wind charge + jump
        boolean onGround = mc.player.isOnGround();
        if (awaitingGroundCombo && !lastOnGround && onGround && !awaitingWindChargeUse) {
            // Only trigger if we have wind charges
            if (!isUsingWindCharge && hasWindCharges()) {
                int windChargeSlot = getWindChargeSlot();
                if (windChargeSlot != -1) {
                    savedMaceSlot = mc.player.getInventory().getSelectedSlot();
                    mc.player.getInventory().setSelectedSlot(windChargeSlot);
                    isUsingWindCharge = true;
                    awaitingWindChargeUse = true;
                    
                    // Set timer for wind charge use (2 ticks = 0.1 seconds)
                    windChargeUseTicks = 2;
                    
                    if (chatFeedback.get()) {
                        mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §bSwitched to wind charge (slot " + windChargeSlot + ")"), false);
                    }
                } else if (chatFeedback.get()) {
                    mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §cNo wind charge found in hotbar!"), false);
                }
            }
            awaitingGroundCombo = false;
        }
        lastOnGround = onGround;
    }

    private void useWindCharge() {
        if (mc.player == null) return;
        
        // Optimize pitch for maximum jump height
        if (optimizePitch.get()) {
            mc.player.setPitch(85.0f); // Slightly less than 90 for better trajectory
        } else {
            mc.player.setPitch(90.0f); // Straight down
        }
        
        // Use wind charge
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        if (chatFeedback.get()) {
            mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §bWind Charge used!"), false);
        }
        
        // Set timer for jump - use instant mode if enabled
        windChargeJumpTicks = instantWindCharge.get() ? 0 : windChargeDelay.get();
        
        // If instant mode, perform jump immediately
        if (instantWindCharge.get()) {
            performWindChargeJump();
        }
        
        awaitingWindChargeUse = false;
    }

    private void performWindChargeJump() {
        if (mc.player == null) return;
        
        // Enhanced jump with better velocity control
        Vec3d currentVelocity = mc.player.getVelocity();
        double jumpBoost = 0.42; // Standard jump strength
        mc.player.setVelocity(currentVelocity.x, jumpBoost, currentVelocity.z);
        
        if (chatFeedback.get()) {
            mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §bJump executed!"), false);
        }
        
        // Switch back to mace immediately
        if (savedMaceSlot != -1) {
            mc.player.getInventory().setSelectedSlot(savedMaceSlot);
            if (chatFeedback.get()) {
                mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §7Switched back to mace (slot " + savedMaceSlot + ")"), false);
            }
        }
        
        isUsingWindCharge = false;
        savedMaceSlot = -1;
    }

    private void startCombo(LivingEntity livingTarget) {
        target = livingTarget;
        comboActive = true;
        comboTicks = 0;
        hits = 1;
        String enchantStatus = " (Using wind charges)";
        if (chatFeedback.get()) {
            mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §aMace combo started!" + enchantStatus), false);
        }
    }

    private void endCombo(String reason) {
        if (chatFeedback.get()) {
            mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §7Combo ended: " + reason + " Hits: " + hits), false);
        }
        reset();
    }

    private void rotateToTarget() {
        Vec3d targetPos = target.getPos();
        Rotations.rotate(Rotations.getYaw(targetPos), Rotations.getPitch(targetPos));
    }

    private void attackTarget() {
        if (mc.interactionManager == null) return;
        mc.interactionManager.attackEntity(mc.player, target);
        mc.player.swingHand(Hand.MAIN_HAND);
        hits++;
    }

    private void switchToMace() {
        if (lockFirstMace.get()) {
            // Lock to the first mace that was used to start the combo
            if (firstUsedMaceSlot != -1 && mc.player.getInventory().getStack(firstUsedMaceSlot).getItem() == Items.MACE) {
                mc.player.getInventory().setSelectedSlot(firstUsedMaceSlot);
                return;
            }
            // Fallback to first mace found if the original slot is empty
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getStack(i).getItem() == Items.MACE) {
                    mc.player.getInventory().setSelectedSlot(i);
                    return;
                }
            }
        } else {
            // Allow any mace - user can switch manually
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getStack(i).getItem() == Items.MACE) {
                    mc.player.getInventory().setSelectedSlot(i);
                    return;
                }
            }
        }
    }

    private boolean hasAnyMaceInHand() {
        return mc.player.getMainHandStack().getItem() == Items.MACE || mc.player.getOffHandStack().getItem() == Items.MACE;
    }

    private boolean hasWindCharges() {
        if (mc.player.getMainHandStack().getItem() == Items.WIND_CHARGE) return true;
        if (mc.player.getOffHandStack().getItem() == Items.WIND_CHARGE) return true;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.WIND_CHARGE) return true;
        }
        return false;
    }

    // Returns the hotbar slot index of a wind charge, or -1 if not found
    private int getWindChargeSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.WIND_CHARGE) return i;
        }
        return -1;
    }
} 