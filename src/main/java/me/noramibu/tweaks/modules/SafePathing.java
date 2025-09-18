package me.noramibu.tweaks.modules;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.Goal;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import me.noramibu.tweaks.NoraTweaks;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Box;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.world.BlockUtils;

/**
 * SafePathing
 *
 * Status: This feature is still under heavy development and may not function properly in all scenarios.
 *
 * Responsibilities:
 * - Keep Baritone avoidance settings tuned (mobs/spawners) while the module is active.
 * - Detect immediate danger (nearby hostiles or recent damage) and enter a panic flow.
 * - Panic flows supported:
 *   RUN_AWAY  -> Temporarily push a Baritone process that sets a flee goal X/Z away from threats.
 *   COVER_NOW -> Cancel active pathing and quickly build a 1x1x2 "safety box" around the player, then wait.
 *   GO_TO_COORDS -> Temporarily push a Baritone process towards user-entered X/Z coordinates.
 *   LOGOUT -> Disconnect from the server.
 *   PAUSE  -> Cancel Baritone pathing and stand still.
 * - Optionally resume the previous Baritone goal after panic ends.
 *
 * Performance notes:
 * - Hostile scanning uses world.getEntitiesByClass within a bounding box and a small cache cooldown.
 * - Flee target recalculation is throttled; retargeting uses a distance threshold (hysteresis).
 * - Cover placement limits blocks per tick and avoids repeated log spam.
 */
public class SafePathing extends Module {
    private final SettingGroup sgAvoid = settings.createGroup("Avoidance");
    private final SettingGroup sgPanic = settings.createGroup("Panic");
    private final SettingGroup sgCover = settings.createGroup("Cover");

    private boolean showCoverSettings() {
        return panicAction.get() == PanicAction.COVER_NOW;
    }

    // Debug / feedback
    private final Setting<Boolean> debugChat = settings.getDefaultGroup().add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Show debug messages in chat when actions trigger.")
        .defaultValue(true)
        .build()
    );

    // Avoidance tuning
    private final Setting<Boolean> enableAvoidance = sgAvoid.add(new BoolSetting.Builder()
        .name("enable-avoidance")
        .description("Enables Baritone's avoidance and configures mob & spawner avoidance.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> mobCoeff = sgAvoid.add(new DoubleSetting.Builder()
        .name("mob-coefficient")
        .description("Path cost multiplier near mobs (>1 to avoid).")
        .defaultValue(5.0)
        .min(1.0)
        .sliderMax(10.0)
        .build()
    );

    private final Setting<Integer> mobRadius = sgAvoid.add(new IntSetting.Builder()
        .name("mob-radius")
        .description("Radius to avoid mobs.")
        .defaultValue(12)
        .min(1)
        .sliderMax(24)
        .build()
    );

    private final Setting<Boolean> avoidSpawners = sgAvoid.add(new BoolSetting.Builder()
        .name("avoid-spawners")
        .description("Avoid mob spawners by default.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> spawnerCoeff = sgAvoid.add(new DoubleSetting.Builder()
        .name("spawner-coefficient")
        .description("Path cost multiplier near spawners.")
        .defaultValue(3.0)
        .min(1.0)
        .sliderMax(10.0)
        .visible(avoidSpawners::get)
        .build()
    );

    private final Setting<Integer> spawnerRadius = sgAvoid.add(new IntSetting.Builder()
        .name("spawner-radius")
        .description("Radius to avoid spawners.")
        .defaultValue(8)
        .min(1)
        .sliderMax(24)
        .visible(avoidSpawners::get)
        .build()
    );

    // Panic behavior
    private final Setting<Boolean> enablePanic = sgPanic.add(new BoolSetting.Builder()
        .name("enable-panic")
        .description("Run away or pause when hostile mobs are too close or after taking damage.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> panicRadius = sgPanic.add(new IntSetting.Builder()
        .name("panic-radius")
        .description("Radius that triggers panic behavior.")
        .defaultValue(8)
        .min(2)
        .sliderMax(24)
        .build()
    );

    public enum PanicAction { RUN_AWAY, COVER_NOW, GO_TO_COORDS, LOGOUT, PAUSE }
    private enum PanicPhase { NONE, FLEE, COVER, WAIT }

    private final Setting<PanicAction> panicAction = sgPanic.add(new EnumSetting.Builder<PanicAction>()
        .name("panic-action")
        .description("Action to take when panic triggers.")
        .defaultValue(PanicAction.RUN_AWAY)
        .build()
    );

    private final Setting<Integer> runDistance = sgPanic.add(new IntSetting.Builder()
        .name("run-distance")
        .description("Blocks to run away when panic-run is enabled.")
        .defaultValue(20)
        .min(8)
        .sliderMax(64)
        .visible(() -> panicAction.get() == PanicAction.RUN_AWAY)
        .build()
    );

    private final Setting<String> panicGoal = sgPanic.add(new StringSetting.Builder()
        .name("panic-goal")
        .description("Target coordinates as 'x,z' or 'x,y,z' for GO_TO_COORDS.")
        .defaultValue("0,0")
        .visible(() -> panicAction.get() == PanicAction.GO_TO_COORDS)
        .build()
    );

    private final Setting<Integer> fleeRecalcInterval = sgPanic.add(new IntSetting.Builder()
        .name("run-recalc-interval-ticks")
        .description("How often to recompute the run direction while panicking.")
        .defaultValue(20)
        .min(5)
        .sliderMax(100)
        .visible(() -> panicAction.get() == PanicAction.RUN_AWAY)
        .build()
    );

    private final Setting<Integer> runRetargetThreshold = sgPanic.add(new IntSetting.Builder()
        .name("run-retarget-threshold")
        .description("Only change run target if it moves by this many blocks.")
        .defaultValue(4)
        .min(1)
        .sliderMax(16)
        .visible(() -> panicAction.get() == PanicAction.RUN_AWAY)
        .build()
    );

    private final Setting<Boolean> autoResume = sgPanic.add(new BoolSetting.Builder()
        .name("auto-resume")
        .description("Resume Baritone's previous goal after panic ends.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> panicGraceTicks = sgPanic.add(new IntSetting.Builder()
        .name("panic-grace-ticks")
        .description("After resuming, ignore new panic triggers for this many ticks.")
        .defaultValue(40)
        .min(0)
        .sliderMax(200)
        .build()
    );

    private final Setting<Integer> safeRadiusMargin = sgPanic.add(new IntSetting.Builder()
        .name("safe-radius-margin")
        .description("Extra blocks beyond panic radius required before ending panic.")
        .defaultValue(4)
        .min(0)
        .sliderMax(16)
        .build()
    );

    private final Setting<Integer> minPanicTicks = sgPanic.add(new IntSetting.Builder()
        .name("min-panic-ticks")
        .description("Minimum ticks to keep panicking once triggered.")
        .defaultValue(40)
        .min(0)
        .sliderMax(200)
        .build()
    );

    private final Setting<Integer> clearConsecutiveTicks = sgPanic.add(new IntSetting.Builder()
        .name("panic-clear-ticks")
        .description("Consecutive safe ticks required before ending panic.")
        .defaultValue(10)
        .min(0)
        .sliderMax(100)
        .build()
    );

    private final Setting<java.util.List<Block>> coverBlocks = sgCover.add(new BlockListSetting.Builder()
        .name("cover-blocks")
        .description("Blocks used to cover yourself.")
        .defaultValue(Blocks.OBSIDIAN, Blocks.CRYING_OBSIDIAN)
        .visible(this::showCoverSettings)
        .build()
    );

    private final Setting<Integer> coverBlocksPerTick = sgCover.add(new IntSetting.Builder()
        .name("cover-blocks-per-tick")
        .description("Max blocks to place per tick while covering.")
        .defaultValue(2)
        .min(1)
        .sliderMax(6)
        .visible(this::showCoverSettings)
        .build()
    );

    private final Setting<Boolean> coverRotate = sgCover.add(new BoolSetting.Builder()
        .name("cover-rotate")
        .description("Rotate towards blocks when placing.")
        .defaultValue(true)
        .visible(this::showCoverSettings)
        .build()
    );

    private final Setting<Boolean> coverAirPlace = sgCover.add(new BoolSetting.Builder()
        .name("cover-air-place")
        .description("Allow placing blocks without support (air place).")
        .defaultValue(false)
        .visible(this::showCoverSettings)
        .build()
    );

    public enum CoverShape { ONE_BY_ONE, BOX, SPHERE }

    private final Setting<CoverShape> coverShape = sgCover.add(new EnumSetting.Builder<CoverShape>()
        .name("cover-shape")
        .description("Cover layout to build around you.")
        .defaultValue(CoverShape.ONE_BY_ONE)
        .visible(this::showCoverSettings)
        .build()
    );

    private final Setting<Integer> boxRadius = sgCover.add(new IntSetting.Builder()
        .name("box-radius")
        .description("Half-size for box cover (in blocks).")
        .defaultValue(1)
        .min(1)
        .sliderMax(4)
        .visible(() -> showCoverSettings() && coverShape.get() == CoverShape.BOX)
        .build()
    );

    private final Setting<Integer> sphereRadius = sgCover.add(new IntSetting.Builder()
        .name("sphere-radius")
        .description("Radius for sphere cover (in blocks).")
        .defaultValue(2)
        .min(1)
        .sliderMax(4)
        .visible(() -> showCoverSettings() && coverShape.get() == CoverShape.SPHERE)
        .build()
    );

    private final Setting<Integer> waitInCoverTicks = sgCover.add(new IntSetting.Builder()
        .name("wait-in-cover-ticks")
        .description("Time to wait inside cover before resuming.")
        .defaultValue(300)
        .min(20)
        .sliderMax(1200)
        .visible(this::showCoverSettings)
        .build()
    );

    private int recentlyHurtTimer;
    private float lastHealth;
    private float lastAbsorption;
    private boolean panicking;
    private boolean pausedByModule;
    private boolean loggedNoBaritone;
    // kept for future use if needed
    private int debugTicker;

    // Panic resume support
    private Goal savedGoalBeforePanic;
    private boolean hasSavedGoalBeforePanic;

    private Vec3d currentFleeTarget;
    private int fleeRecalcTimer;
    private int panicGraceTimer;
    private IBaritoneProcess fleeProcess;
    private int panicTicks;
    private int safeTicks;
    private java.util.List<BlockPos> coverQueue = new java.util.ArrayList<>();
    private boolean coverActive;
    private int coverRetryCooldown;
    private boolean coverLoggedOnce;
    private boolean coverNoBlocksLogged;
    private PanicPhase panicPhase = PanicPhase.NONE;
    private int waitCoverTicksLeft;
    private int safeWhileFleeTicks;
    private NearestCache nearestCache = new NearestCache();

    // Avoidance log spam prevention
    private boolean settingsSnapshotInitialized;
    private double lastMobCoeffApplied;
    private int lastMobRadiusApplied;
    private boolean lastAvoidSpawnersApplied;
    private double lastSpawnerCoeffApplied;
    private int lastSpawnerRadiusApplied;

    public SafePathing() {
        super(NoraTweaks.BARITONE_CATEGORY, "safe-pathing", "Baritone avoidance tuning and panic behavior.");
    }

    @Override
    public void onActivate() {
        // Initialize per-activation state and apply avoidance settings once.
        applyAvoidanceSettings();
        recentlyHurtTimer = 0;
        if (mc.player != null) {
            lastHealth = mc.player.getHealth();
            lastAbsorption = mc.player.getAbsorptionAmount();
        }
        panicking = false;
        pausedByModule = false;
        loggedNoBaritone = false;
        debugTicker = 0;
        currentFleeTarget = null;
        fleeRecalcTimer = 0;
        panicGraceTimer = 0;
        panicTicks = 0;
        safeTicks = 0;
        coverQueue.clear();
        coverActive = false;
        coverRetryCooldown = 0;
        coverLoggedOnce = false;
        coverNoBlocksLogged = false;
        panicPhase = PanicPhase.NONE;
        waitCoverTicksLeft = 0;
        safeWhileFleeTicks = 0;
        nearestCache.reset();
        // Always show an activation line so users know the module is running
        info("SafePathing activated.");

        // Register a temporary Baritone process used for fleeing, so we don't cancel existing tasks (like mining).
        try {
            if (fleeProcess == null) fleeProcess = new FleeProcess();
            BaritoneAPI.getProvider().getPrimaryBaritone().getPathingControlManager().registerProcess(fleeProcess);
        } catch (Throwable ignored) {
        }
        // Ensure cover queue starts empty
        coverQueue.clear();
        coverActive = false;

        if (debugChat.get()) {
            try {
                Class.forName("baritone.api.BaritoneAPI");
                info("Baritone detected.");
            } catch (Throwable t) {
                info("Baritone not detected.");
            }
        }
    }

    @Override
    public void onDeactivate() {
        // If a panic-run/go-to process is active, deactivate it so Baritone stops heading there.
        try {
            if (fleeProcess instanceof FleeProcess fp) fp.deactivate();
        } catch (Throwable ignored) {}
        // Stop any in-progress cover placement and clear panic state.
        coverActive = false;
        coverQueue.clear();
        panicking = false;
        pausedByModule = false;
        panicPhase = PanicPhase.NONE;
        currentFleeTarget = null;
        fleeRecalcTimer = 0;
    }

    /**
     * Main tick loop
     * - Keeps avoidance settings in sync
     * - Detects danger and manages panic lifecycle (enter, handle, exit)
     * - Drives cover placement during COVER/WAIT phases
     */
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!isActiveAndWorld()) return;

        // Keep avoidance in sync (in case other mods change it)
        applyAvoidanceSettings();

        if (!enablePanic.get()) return;

        if (panicGraceTimer > 0) {
            panicGraceTimer--;
            return;
        }

        // Tick down cover retry cooldown
        if (coverRetryCooldown > 0) coverRetryCooldown--;

        // Detect recent damage via health/absorption drop
        float health = mc.player.getHealth();
        float absorption = mc.player.getAbsorptionAmount();
        if (health + absorption < lastHealth + lastAbsorption - 0.1f) {
            recentlyHurtTimer = 20; // ~1s at 20 tps
        }
        lastHealth = health;
        lastAbsorption = absorption;

        if (recentlyHurtTimer > 0) recentlyHurtTimer--;

        // Scan hostiles with a small cached interval. If already panicking or recently hurt, scan more frequently.
        boolean inPanic = panicking || recentlyHurtTimer > 0;
        NearestResult nr = scanNearestHostileCached(panicRadius.get() + 4, inPanic ? 1 : 3);
        Entity nearestHostile = nr.entity != null && nr.distSq <= (double) panicRadius.get() * (double) panicRadius.get() ? nr.entity : null;
        Entity nearestHostileHyst = nr.entity != null ? nr.entity : null;
        // Optional periodic debug (kept minimal)
        if (debugChat.get() && nearestHostile != null && (debugTicker++ % 40 == 0)) {
            double dist = mc.player.getPos().distanceTo(nearestHostile.getPos());
            info("Debug: hostile %s at %.1f blocks.", nearestHostile.getName().getString(), dist);
        }
        // Compute panic trigger/safe state with hysteresis
        boolean triggerPanic = nearestHostile != null || recentlyHurtTimer > 0 || coverActive;
        boolean safeNow = (nearestHostileHyst == null) && recentlyHurtTimer <= 0;
        if (!triggerPanic && panicking) {
            panicTicks++;
            if (safeNow) safeTicks++; else safeTicks = 0;
            boolean canEnd = panicTicks >= 40 && safeTicks >= 10;
            if (canEnd && autoResume.get()) {
                try {
                    var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                    if (hasSavedGoalBeforePanic && savedGoalBeforePanic != null) {
                        baritone.getCustomGoalProcess().setGoalAndPath(savedGoalBeforePanic);
                        if (debugChat.get()) info("Panic over: resuming previous Baritone goal.");
                    }
                    if (fleeProcess instanceof FleeProcess fp) fp.deactivate();
                } catch (Throwable ignored) {}
                panicking = false;
                pausedByModule = false;
                hasSavedGoalBeforePanic = false;
                savedGoalBeforePanic = null;
                currentFleeTarget = null;
                fleeRecalcTimer = 0;
                panicGraceTimer = panicGraceTicks.get();
                panicTicks = 0;
                safeTicks = 0;
            }
        }
        if (!triggerPanic) return;

        // Drive cover placement in panic loop only when in COVER or WAIT phases
        if (panicPhase == PanicPhase.COVER || panicPhase == PanicPhase.WAIT) tickCoverPlacement();

        // If cover finished and waiting is done or area safe, end panic (COVER_NOW flow)
        if (!coverActive && panicking && (panicPhase == PanicPhase.COVER || panicPhase == PanicPhase.WAIT)) {
            if (panicPhase == PanicPhase.COVER) {
                panicPhase = PanicPhase.WAIT;
                waitCoverTicksLeft = waitInCoverTicks.get();
            }
            if (panicPhase == PanicPhase.WAIT) {
                if (waitCoverTicksLeft > 0) waitCoverTicksLeft--;
                boolean waitDone = waitCoverTicksLeft <= 0;
                if (waitDone && recentlyHurtTimer <= 0 && nr.entity == null && autoResume.get()) {
                    endPanicAndResume();
                    return;
                }
            }
        }

        try {
            if (panicAction.get() == PanicAction.RUN_AWAY) {
                Vec3d playerPos = mc.player.getPos();
                Vec3d target = computeFleeTarget(playerPos, panicRadius.get(), runDistance.get());
                if (target == null) target = playerPos.add(1, 0, 0);
                boolean needSet = !panicking || currentFleeTarget == null;
                if (!needSet && fleeRecalcTimer > 0) fleeRecalcTimer--;
                if (!needSet && fleeRecalcTimer == 0) {
                    double delta = currentFleeTarget.distanceTo(target);
                    if (delta >= 4) needSet = true;
                }
                if (needSet) {
                    int tx = (int) Math.floor(target.x);
                    int tz = (int) Math.floor(target.z);
                    if (!panicking) {
                        try {
                            savedGoalBeforePanic = BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().getGoal();
                            hasSavedGoalBeforePanic = savedGoalBeforePanic != null;
                        } catch (Throwable ignored) {
                            hasSavedGoalBeforePanic = false;
                            savedGoalBeforePanic = null;
                        }
                    }
                    if (fleeProcess instanceof FleeProcess fp) fp.setGoal(new GoalXZ(tx, tz));
                    currentFleeTarget = target;
                    fleeRecalcTimer = 20;
                    if (debugChat.get() && !panicking) {
                        info("Panic run: setting flee target (%d, %d) ~%d blocks away.", tx, tz, runDistance.get());
                    }
                    // Cover is a separate action (COVER_NOW), not mixed into RUN_AWAY
                }
                panicking = true;
                pausedByModule = false;
                panicTicks++;
                if (panicAction.get() == PanicAction.RUN_AWAY) {
                    panicPhase = PanicPhase.FLEE;
                }
                return;
            } else if (panicAction.get() == PanicAction.GO_TO_COORDS) {
                // Parse panicGoal as "x,z" or "x,y,z"; y is optional and unused for GoalXZ
                int tx; int tz;
                String goalStr = panicGoal.get();
                int px = (int) Math.floor(mc.player.getX());
                int pz = (int) Math.floor(mc.player.getZ());
                try {
                    String[] parts = goalStr.split(",");
                    if (parts.length >= 2) {
                        tx = Integer.parseInt(parts[0].trim());
                        tz = Integer.parseInt(parts[1].trim());
                    } else {
                        tx = px; tz = pz;
                    }
                } catch (Exception ex) {
                    tx = px; tz = pz;
                }
                boolean needSet = !panicking || currentFleeTarget == null;
                if (!needSet && fleeRecalcTimer > 0) fleeRecalcTimer--;
                if (needSet || fleeRecalcTimer == 0) {
                    if (!panicking) {
                        try {
                            savedGoalBeforePanic = BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().getGoal();
                            hasSavedGoalBeforePanic = savedGoalBeforePanic != null;
                        } catch (Throwable ignored) {
                            hasSavedGoalBeforePanic = false;
                            savedGoalBeforePanic = null;
                        }
                    }
                    if (fleeProcess instanceof FleeProcess fp) fp.setGoal(new GoalXZ(tx, tz));
                    currentFleeTarget = new Vec3d(tx + 0.5, mc.player.getY(), tz + 0.5);
                    fleeRecalcTimer = 20;
                    if (debugChat.get() && !panicking) info("Panic goto: heading to (%d, %d).", tx, tz);
                }
                panicking = true;
                pausedByModule = false;
                panicTicks++;
                if (safeNow) safeTicks++; else safeTicks = 0;
                return;
            } else if (panicAction.get() == PanicAction.COVER_NOW) {
                if (!panicking) {
                    try {
                        savedGoalBeforePanic = BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().getGoal();
                        hasSavedGoalBeforePanic = savedGoalBeforePanic != null;
                    } catch (Throwable ignored) {
                        hasSavedGoalBeforePanic = false;
                        savedGoalBeforePanic = null;
                    }
                }
                // Cancel Baritone pathing so manual block placement isn't disturbed
                BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
                if (!coverActive && coverRetryCooldown == 0) {
                    prepareCoverQueue();
                    coverActive = true;
                    if (debugChat.get() && !coverLoggedOnce) { info("Panic cover: building quick cover."); coverLoggedOnce = true; }
                }
                pausedByModule = true;
                panicking = true;
                panicPhase = PanicPhase.COVER;
                return;
            } else if (panicAction.get() == PanicAction.LOGOUT) {
                try {
                    if (mc.getNetworkHandler() != null && mc.getNetworkHandler().getConnection() != null) {
                        mc.getNetworkHandler().getConnection().disconnect(net.minecraft.text.Text.of("Safe Pathing panic"));
                        if (debugChat.get()) info("Panic logout: disconnecting.");
                    }
                } catch (Throwable ignored) {
                }
                panicking = true;
                pausedByModule = true;
                return;
            } else { // PAUSE
                if (!panicking) {
                    try {
                        savedGoalBeforePanic = BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().getGoal();
                        hasSavedGoalBeforePanic = savedGoalBeforePanic != null;
                    } catch (Throwable ignored) {
                        hasSavedGoalBeforePanic = false;
                        savedGoalBeforePanic = null;
                    }
                }
                BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().cancelEverything();
                if (debugChat.get() && !pausedByModule) info("Panic pause (direct): cancelling Baritone pathing.");
                pausedByModule = true;
                panicking = true;
                return;
            }
        } catch (Throwable t) {
            if (debugChat.get() && !loggedNoBaritone) {
                loggedNoBaritone = true;
                warning("Baritone API call failed: %s", t.getClass().getSimpleName());
            }
        }
    }

    /**
     * End panic and resume the previously saved Baritone goal, if any.
     */
    private void endPanicAndResume() {
        try {
            var baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            if (hasSavedGoalBeforePanic && savedGoalBeforePanic != null) {
                baritone.getCustomGoalProcess().setGoalAndPath(savedGoalBeforePanic);
                if (debugChat.get()) info("Panic over: resuming previous Baritone goal.");
            }
            if (fleeProcess instanceof FleeProcess fp) fp.deactivate();
        } catch (Throwable ignored) {}
        panicking = false;
        pausedByModule = false;
        hasSavedGoalBeforePanic = false;
        savedGoalBeforePanic = null;
        currentFleeTarget = null;
        fleeRecalcTimer = 0;
        panicGraceTimer = panicGraceTicks.get();
        panicTicks = 0;
        safeTicks = 0;
        coverLoggedOnce = false;
        coverRetryCooldown = 0;
    }

    /**
     * Scan for nearest hostile within radius with a short cooldown to reduce CPU usage.
     * Uses a bounding box around the player to query only entities in range.
     */
    private NearestResult scanNearestHostileCached(double radius, int intervalTicks) {
        if (mc.world == null || mc.player == null) return NearestResult.EMPTY;
        if (nearestCache.cooldown > 0) { nearestCache.cooldown--; return nearestCache.last; }
        Box box = mc.player.getBoundingBox().expand(radius);
        double best = Double.MAX_VALUE;
        HostileEntity bestEntity = null;
        for (HostileEntity h : mc.world.getEntitiesByClass(HostileEntity.class, box, Entity::isAlive)) {
            double d = h.squaredDistanceTo(mc.player);
            if (d < best) { best = d; bestEntity = h; }
        }
        nearestCache.last = bestEntity == null ? NearestResult.EMPTY : new NearestResult(bestEntity, best);
        nearestCache.cooldown = Math.max(1, intervalTicks);
        return nearestCache.last;
    }

    private boolean isActiveAndWorld() {
        return isActive() && mc.player != null && mc.world != null;
    }

    /**
     * Keep Baritone avoidance settings aligned with current module settings.
     * Only log when the effective values actually change to prevent chat spam.
     */
    private void applyAvoidanceSettings() {
        if (!enableAvoidance.get()) return;
        var s = BaritoneAPI.getSettings();
        // Determine if settings changed since last snapshot
        boolean changed = !settingsSnapshotInitialized
            || lastMobCoeffApplied != mobCoeff.get()
            || lastMobRadiusApplied != mobRadius.get()
            || lastAvoidSpawnersApplied != avoidSpawners.get()
            || (avoidSpawners.get() && (lastSpawnerCoeffApplied != spawnerCoeff.get() || lastSpawnerRadiusApplied != spawnerRadius.get()));

        s.avoidance.value = true;
        s.mobAvoidanceCoefficient.value = mobCoeff.get();
        s.mobAvoidanceRadius.value = mobRadius.get();
        s.mobSpawnerAvoidanceCoefficient.value = avoidSpawners.get() ? spawnerCoeff.get() : 1.0;
        s.mobSpawnerAvoidanceRadius.value = avoidSpawners.get() ? spawnerRadius.get() : 0;

        // Update snapshot and report only on change
        settingsSnapshotInitialized = true;
        lastMobCoeffApplied = mobCoeff.get();
        lastMobRadiusApplied = mobRadius.get();
        lastAvoidSpawnersApplied = avoidSpawners.get();
        lastSpawnerCoeffApplied = avoidSpawners.get() ? spawnerCoeff.get() : 1.0;
        lastSpawnerRadiusApplied = avoidSpawners.get() ? spawnerRadius.get() : 0;

        if (changed && debugChat.get()) info("Applied Baritone settings (direct).");
    }

    /**
     * Compute a flee target by aggregating repulsion vectors from all hostiles within radius.
     * Hostiles farther away contribute less (1 / distance^2) for stable multi-threat results.
     */
    private Vec3d computeFleeTarget(Vec3d playerPos, double radius, int distance) {
        if (mc.world == null || mc.player == null) return null;
        double radiusSq = radius * radius;
        Box box = mc.player.getBoundingBox().expand(radius);
        Vec3d away = Vec3d.ZERO;
        for (HostileEntity hostile : mc.world.getEntitiesByClass(HostileEntity.class, box, Entity::isAlive)) {
            double d2 = hostile.squaredDistanceTo(playerPos.x, playerPos.y, playerPos.z);
            if (d2 > radiusSq) continue;
            Vec3d dir = playerPos.subtract(hostile.getPos());
            double len2 = dir.lengthSquared();
            if (len2 < 1e-6) continue;
            double weight = 1.0 / len2;
            away = away.add(dir.normalize().multiply(weight));
        }
        if (away.lengthSquared() < 1e-6) return playerPos.add(1, 0, 0);
        Vec3d dir = away.normalize();
        return playerPos.add(dir.multiply(distance));
    }

    /**
     * Prepare the block positions required to build the safety structure.
     * ONE_BY_ONE layout matches a common "safety box" design (credit: @etianl InstaSafetyBox).
     */
    private void prepareCoverQueue() {
        coverQueue.clear();
        if (mc.player == null) return;
        BlockPos feet = BlockPos.ofFloored(mc.player.getX(), Math.floor(mc.player.getBoundingBox().minY), mc.player.getZ());
        BlockPos head = feet.up();
        switch (coverShape.get()) {
            case ONE_BY_ONE -> {
                // Safety box layout inspired by InstaSafetyBox (credit: @etianl)
                coverQueue.add(feet.down());
                for (Direction d : new Direction[] { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST }) {
                    coverQueue.add(feet.offset(d));
                    coverQueue.add(head.offset(d));
                }
                coverQueue.add(head.up());
            }
            case BOX -> {
                int r = boxRadius.get();
                // floor
                for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) coverQueue.add(feet.down().add(dx, 0, dz));
                // walls feet+head layers
                for (int y = 0; y <= 1; y++) {
                    for (int dx = -r; dx <= r; dx++) {
                        coverQueue.add(feet.add(dx, y, -r));
                        coverQueue.add(feet.add(dx, y, r));
                    }
                    for (int dz = -r; dz <= r; dz++) {
                        coverQueue.add(feet.add(-r, y, dz));
                        coverQueue.add(feet.add(r, y, dz));
                    }
                }
                // roof
                for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) coverQueue.add(head.up().add(dx, 0, dz));
            }
            case SPHERE -> {
                int r = sphereRadius.get();
                int rr = r * r;
                for (int dx = -r; dx <= r; dx++) {
                    for (int dy = 0; dy <= r + 1; dy++) {
                        for (int dz = -r; dz <= r; dz++) {
                            int dd = dx*dx + dy*dy + dz*dz;
                            if (dd <= rr) coverQueue.add(feet.add(dx, dy, dz));
                        }
                    }
                }
            }
        }
    }

    /**
     * Drives per-tick block placement for the safety structure with simple rate limiting.
     * Automatically attempts to place a temporary support when no place face exists.
     */
    private void tickCoverPlacement() {
        if (!coverActive) return;
        if (coverQueue.isEmpty()) { coverActive = false; return; }
        FindItemResult block = InvUtils.findInHotbar(itemStack -> coverBlocks.get().contains(Block.getBlockFromItem(itemStack.getItem())));
        if (!block.found()) {
            if (!coverNoBlocksLogged && debugChat.get()) { info("Cover paused: no cover blocks in hotbar."); coverNoBlocksLogged = true; }
            coverActive = false;
            coverRetryCooldown = 40;
            return;
        }
        coverNoBlocksLogged = false;
        int placed = 0;
        java.util.Iterator<BlockPos> it = coverQueue.iterator();
        while (it.hasNext() && placed < 2) {
            BlockPos pos = it.next();
            if (!BlockUtils.canPlace(pos)) { it.remove(); continue; }
            // If no support face, try to place a one-off support block on a neighbor first
            if (BlockUtils.getPlaceSide(pos) == null) {
                boolean supported = false;
                for (Direction d : new Direction[] { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP, Direction.DOWN }) {
                    BlockPos support = pos.offset(d);
                    if (BlockUtils.canPlace(support) && BlockUtils.getPlaceSide(support) != null) {
                        if (BlockUtils.place(support, block, coverRotate.get(), 50, true)) { supported = true; break; }
                    }
                }
                if (!supported) { it.remove(); continue; }
            }
            if (BlockUtils.place(pos, block, true, 50, true)) {
                it.remove();
                placed++;
            }
        }
        if (coverQueue.isEmpty()) {
            coverActive = false; // done
        }
    }

    /** Result holder for nearest-hostile query. */
    private static final class NearestResult {
        static final NearestResult EMPTY = new NearestResult(null, Double.MAX_VALUE);
        final Entity entity; final double distSq;
        NearestResult(Entity e, double d) { this.entity = e; this.distSq = d; }
    }

    /** Tiny cooldown cache for nearest-hostile to avoid scanning every tick. */
    private static final class NearestCache {
        int cooldown = 0;
        NearestResult last = NearestResult.EMPTY;
        void reset() { cooldown = 0; last = NearestResult.EMPTY; }
    }
}

/**
 * Lightweight temporary Baritone process used to push a flee/goal without tearing down
 * the user's existing high-level task (e.g., mining). When panic ends, we deactivate it
 * and Baritone continues the prior process.
 */
class FleeProcess implements IBaritoneProcess {
    private Goal goal;
    private boolean active;

    @Override
    public boolean isActive() {
        return active && goal != null;
    }

    public void setGoal(Goal goal) {
        this.goal = goal;
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
        this.goal = null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (!isActive()) return null;
        return new PathingCommand(goal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    @Override
    public boolean isTemporary() {
        return true;
    }

    @Override
    public void onLostControl() {
        deactivate();
    }

    @Override
    public String displayName0() {
        return "Flee";
    }
}


