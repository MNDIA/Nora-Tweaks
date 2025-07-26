package me.noramibu.tweaks.modules;

import me.noramibu.tweaks.NoraTweaks;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.TickEvent.Pre;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class AutoLogStrip extends Module {
   private final SettingGroup sgGeneral;
   private final SettingGroup sgOperation;
   private final Setting<Boolean> autoRotate;
   private final Setting<Boolean> chatFeedback;
   private final Setting<Integer> stripDelay;
   private final Setting<Integer> breakDelay;
   private final Setting<Boolean> autoPlace;
   private final Setting<Boolean> refillOffHand;
   private final Setting<Boolean> refillWithAnyLog;
   private final Setting<Double> maxRange;
   private final Setting<Boolean> quietMode;
   private final Setting<Boolean> smartBreaking;
   private BlockPos lastPlacedLog;
   private int stripTimer;
   private int breakTimer;
   private boolean waitingToStrip;
   private boolean waitingToBreak;
   private boolean waitingToPlace;
   private int placeTimer;
   private Item firstLogType;

   public AutoLogStrip() {
      super(NoraTweaks.CATEGORY, "auto-log-strip", "Automatically places, strips, and breaks logs for efficient processing.");
      this.sgGeneral = this.settings.getDefaultGroup();
      this.sgOperation = this.settings.createGroup("Operation");
      
      this.autoRotate = this.sgGeneral.add(new BoolSetting.Builder()
            .name("auto-rotate")
            .description("Automatically rotate to face target blocks.")
            .defaultValue(true)
            .build());
            
      this.chatFeedback = this.sgGeneral.add(new BoolSetting.Builder()
            .name("chat-feedback")
            .description("Show operation status messages.")
            .defaultValue(true)
            .build());
            
      this.stripDelay = this.sgOperation.add(new IntSetting.Builder()
            .name("strip-delay")
            .description("Delay in ticks before stripping (20 ticks = 1 second).")
            .defaultValue(2)
            .min(1)
            .max(20)
            .sliderMax(10)
            .build());
            
      this.breakDelay = this.sgOperation.add(new IntSetting.Builder()
            .name("break-delay")
            .description("Delay in ticks before breaking (20 ticks = 1 second).")
            .defaultValue(2)
            .min(1)
            .max(20)
            .sliderMax(10)
            .build());
            
      this.autoPlace = this.sgGeneral.add(new BoolSetting.Builder()
            .name("auto-place")
            .description("Automatically place logs after breaking stripped logs.")
            .defaultValue(true)
            .build());
            
      this.refillOffHand = this.sgGeneral.add(new BoolSetting.Builder()
            .name("refill-off-hand")
            .description("Automatically refill off-hand with logs when empty.")
            .defaultValue(true)
            .build());
            
      this.refillWithAnyLog = this.sgGeneral.add(new BoolSetting.Builder()
            .name("refill-with-any-log")
            .description("Refill off-hand with any log type. If false, only uses the same type as first log.")
            .defaultValue(false)
            .visible(() -> this.refillOffHand.get())
            .build());
            
      // New settings for improved functionality
      this.maxRange = this.sgOperation.add(new DoubleSetting.Builder()
            .name("max-range")
            .description("Maximum range to operate on logs (blocks).")
            .defaultValue(5.0)
            .min(1.0)
            .max(10.0)
            .sliderMax(10.0)
            .build());
            
      this.quietMode = this.sgGeneral.add(new BoolSetting.Builder()
            .name("quiet-mode")
            .description("Reduce chat feedback to essential messages only.")
            .defaultValue(false)
            .build());
            
      this.smartBreaking = this.sgOperation.add(new BoolSetting.Builder()
            .name("smart-breaking")
            .description("Use optimized block breaking method.")
            .defaultValue(true)
            .build());
      this.lastPlacedLog = null;
      this.stripTimer = 0;
      this.breakTimer = 0;
      this.waitingToStrip = false;
      this.waitingToBreak = false;
      this.waitingToPlace = false;
      this.placeTimer = 0;
      this.firstLogType = null;
   }

   public void onActivate() {
      this.reset();
   }

   public void onDeactivate() {
      this.reset();
   }

   private void reset() {
      this.lastPlacedLog = null;
      this.stripTimer = 0;
      this.breakTimer = 0;
      this.placeTimer = 0;
      this.waitingToStrip = false;
      this.waitingToBreak = false;
      this.waitingToPlace = false;
      this.firstLogType = null;
   }

   @EventHandler
   private void onBlockUpdate(BlockUpdateEvent event) {
      if (this.mc.player == null || this.mc.world == null) return;
      
      if (event.oldState.isAir() && this.isLogBlock(event.newState.getBlock())) {
         double distance = this.mc.player.squaredDistanceTo(
            (double)event.pos.getX() + 0.5D, 
            (double)event.pos.getY() + 0.5D, 
            (double)event.pos.getZ() + 0.5D
         );
         
         double maxRangeSquared = this.maxRange.get() * this.maxRange.get();
         if (distance <= maxRangeSquared) {
            boolean showDebug = this.chatFeedback.get() && !this.quietMode.get();
            
            if (showDebug) {
               this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §eDetected log placement at " + event.pos + " (distance: " + String.format("%.1f", Math.sqrt(distance)) + ")"), false);
            }

            if (this.firstLogType == null) {
               this.firstLogType = event.newState.getBlock().asItem();
               if (this.chatFeedback.get()) {
                  this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §aFirst log type set to: " + this.firstLogType.toString().replace("minecraft:", "").replace("_log", "").toUpperCase()), false);
               }
            }

            this.onLogPlaced(event.pos);
         }
      }
   }

   @EventHandler
   private void onTick(Pre event) {
      if (this.mc.player != null && this.mc.world != null) {
         if (this.waitingToPlace && this.placeTimer > 0) {
            --this.placeTimer;
            if (this.placeTimer == 0) {
               this.placeLog();
            }
         }

         if (this.lastPlacedLog != null) {
            BlockState state = this.mc.world.getBlockState(this.lastPlacedLog);
            Block block = state.getBlock();
            if (this.isLogBlock(block)) {
               if (!this.waitingToStrip && !this.waitingToBreak) {
                  this.waitingToStrip = true;
                  this.stripTimer = (Integer)this.stripDelay.get();
                  if ((Boolean)this.chatFeedback.get()) {
                     this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §aLog detected, will strip in " + String.valueOf(this.stripDelay.get()) + " ticks"), false);
                  }
               }
            } else if (this.isStrippedLogBlock(block)) {
               if ((Boolean)this.chatFeedback.get() && (this.waitingToStrip || this.waitingToBreak)) {
                  this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §eDetected stripped log, waiting for break timer..."), false);
               }
            } else {
               this.lastPlacedLog = null;
               this.waitingToStrip = false;
               this.waitingToBreak = false;
            }
         }

         if (this.waitingToStrip && this.stripTimer > 0) {
            --this.stripTimer;
            if ((Boolean)this.chatFeedback.get() && this.stripTimer % 10 == 0) {
               this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §eStrip timer: " + this.stripTimer + " ticks remaining"), false);
            }

            if (this.stripTimer == 0) {
               this.stripLog();
            }
         }

         if (this.waitingToBreak && this.breakTimer > 0) {
            --this.breakTimer;
            if ((Boolean)this.chatFeedback.get() && this.breakTimer % 10 == 0) {
               this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §eBreak timer: " + this.breakTimer + " ticks remaining"), false);
            }

            if (this.breakTimer == 0) {
               if ((Boolean)this.chatFeedback.get()) {
                  this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §aBreak timer expired, calling breakLog()"), false);
               }

               this.breakLog();
            }
         }

      }
   }

   private boolean isLogBlock(Block block) {
      return block == Blocks.OAK_LOG || block == Blocks.SPRUCE_LOG || block == Blocks.BIRCH_LOG || 
             block == Blocks.JUNGLE_LOG || block == Blocks.ACACIA_LOG || block == Blocks.DARK_OAK_LOG || 
             block == Blocks.MANGROVE_LOG || block == Blocks.CHERRY_LOG || block == Blocks.BAMBOO_BLOCK || 
             block == Blocks.CRIMSON_STEM || block == Blocks.WARPED_STEM;
   }

   private boolean isStrippedLogBlock(Block block) {
      return block == Blocks.STRIPPED_OAK_LOG || block == Blocks.STRIPPED_SPRUCE_LOG || 
             block == Blocks.STRIPPED_BIRCH_LOG || block == Blocks.STRIPPED_JUNGLE_LOG || 
             block == Blocks.STRIPPED_ACACIA_LOG || block == Blocks.STRIPPED_DARK_OAK_LOG || 
             block == Blocks.STRIPPED_MANGROVE_LOG || block == Blocks.STRIPPED_CHERRY_LOG || 
             block == Blocks.STRIPPED_BAMBOO_BLOCK || block == Blocks.STRIPPED_CRIMSON_STEM || 
             block == Blocks.STRIPPED_WARPED_STEM;
   }

   private void stripLog() {
      if (this.lastPlacedLog != null) {
         BlockState state = this.mc.world.getBlockState(this.lastPlacedLog);
         Block block = state.getBlock();
         ClientPlayerEntity var10000;
         String var10001;
         if ((Boolean)this.chatFeedback.get()) {
            var10000 = this.mc.player;
            var10001 = String.valueOf(this.lastPlacedLog);
            var10000.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §eAttempting to strip log at " + var10001 + " (block: " + String.valueOf(block) + ")"), false);
         }

         if (!this.isLogBlock(block)) {
            if ((Boolean)this.chatFeedback.get()) {
               this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §cBlock is not a log: " + String.valueOf(block)), false);
            }

            this.waitingToStrip = false;
         } else if (!this.hasAxe()) {
            if ((Boolean)this.chatFeedback.get()) {
               this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §cNo axe found for stripping!"), false);
            }

            this.waitingToStrip = false;
         } else {
            if ((Boolean)this.autoRotate.get()) {
               this.rotateToBlock(this.lastPlacedLog);
            }

            if (this.mc.interactionManager != null) {
               this.mc.interactionManager.interactBlock(this.mc.player, Hand.MAIN_HAND, new BlockHitResult(Vec3d.ofCenter(this.lastPlacedLog), Direction.UP, this.lastPlacedLog, false));
               this.mc.player.swingHand(Hand.MAIN_HAND);
               if ((Boolean)this.chatFeedback.get()) {
                  this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §bStripped log at " + String.valueOf(this.lastPlacedLog)), false);
               }

               this.waitingToStrip = false;
               this.waitingToBreak = true;
               this.breakTimer = (Integer)this.breakDelay.get();
               if ((Boolean)this.chatFeedback.get()) {
                  var10000 = this.mc.player;
                  var10001 = String.valueOf(this.breakDelay.get());
                  var10000.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §aWill break in " + var10001 + " ticks (timer set: " + this.breakTimer + ")"), false);
               }
            }

         }
      }
   }

   private void breakLog() {
      if (this.lastPlacedLog == null || this.mc.interactionManager == null) return;
      
      // Check if block is still in range
      double distance = this.mc.player.squaredDistanceTo(
         (double)this.lastPlacedLog.getX() + 0.5D,
         (double)this.lastPlacedLog.getY() + 0.5D,
         (double)this.lastPlacedLog.getZ() + 0.5D
      );
      double maxRangeSquared = this.maxRange.get() * this.maxRange.get();
      if (distance > maxRangeSquared) {
         if (this.chatFeedback.get()) {
            this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §cLog too far away, aborting break"), false);
         }
         this.waitingToBreak = false;
         return;
      }
      
      BlockState state = this.mc.world.getBlockState(this.lastPlacedLog);
      Block block = state.getBlock();
      boolean showDebug = this.chatFeedback.get() && !this.quietMode.get();
      
      if (showDebug) {
         this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §eBreaking stripped log at " + this.lastPlacedLog), false);
      }

      if (!this.isStrippedLogBlock(block)) {
         if (this.chatFeedback.get()) {
            this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §cBlock is not a stripped log: " + block.toString().replace("minecraft:", "")), false);
         }
         this.waitingToBreak = false;
         return;
      }
      
      if (this.mc.player.getMainHandStack().isEmpty() && this.mc.player.getOffHandStack().isEmpty()) {
         if (this.chatFeedback.get()) {
            this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §cNo tool in hand for breaking!"), false);
         }
         this.waitingToBreak = false;
         return;
      }
      
      if (this.autoRotate.get()) {
         this.rotateToBlock(this.lastPlacedLog);
      }

      // Use smart breaking or traditional method
      if (this.smartBreaking.get()) {
         // Optimized breaking - single attack with proper timing
         this.mc.interactionManager.attackBlock(this.lastPlacedLog, Direction.UP);
         this.mc.player.swingHand(Hand.MAIN_HAND);
         this.mc.interactionManager.updateBlockBreakingProgress(this.lastPlacedLog, Direction.UP);
      } else {
         // Traditional method with multiple updates
         this.mc.interactionManager.attackBlock(this.lastPlacedLog, Direction.UP);
         this.mc.player.swingHand(Hand.MAIN_HAND);
         for(int i = 0; i < 10; ++i) {
            this.mc.interactionManager.updateBlockBreakingProgress(this.lastPlacedLog, Direction.UP);
         }
      }

      if (this.chatFeedback.get()) {
         this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §aBroke stripped log"), false);
      }

      this.waitingToBreak = false;
      BlockPos brokenPosition = this.lastPlacedLog;
      this.lastPlacedLog = null;
      
      if (this.autoPlace.get()) {
         this.waitingToPlace = true;
         this.placeTimer = 1;
         this.lastPlacedLog = brokenPosition;
         if (showDebug) {
            this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §aWill place new log to continue cycle"), false);
         }
      } else if (this.chatFeedback.get()) {
         this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §aCycle complete! Ready for next log."), false);
      }
   }

   private void rotateToBlock(BlockPos pos) {
      Vec3d blockCenter = Vec3d.ofCenter(pos);
      Rotations.rotate(Rotations.getYaw(blockCenter), Rotations.getPitch(blockCenter));
   }

   private boolean hasAxe() {
      ItemStack mainHand = this.mc.player.getMainHandStack();
      ItemStack offHand = this.mc.player.getOffHandStack();
      return mainHand.getItem() instanceof AxeItem || offHand.getItem() instanceof AxeItem;
   }

   private void placeLog() {
      if ((Boolean)this.autoPlace.get()) {
         BlockPos placePos = this.lastPlacedLog;
         if (placePos == null) {
            if ((Boolean)this.chatFeedback.get()) {
               this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §cNo position saved for log placement!"), false);
            }

            this.waitingToPlace = false;
         } else {
            ItemStack offHand = this.mc.player.getOffHandStack();
            if (!this.isLogItem(offHand.getItem()) || offHand.getCount() <= 1) {
               if (!(Boolean)this.refillOffHand.get()) {
                  if ((Boolean)this.chatFeedback.get()) {
                     this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §cNo logs in off-hand and auto-refill is disabled!"), false);
                  }

                  this.waitingToPlace = false;
                  return;
               }

               if (!this.refillOffHand()) {
                  if ((Boolean)this.chatFeedback.get()) {
                     this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §cNo logs available to refill off-hand!"), false);
                  }

                  this.waitingToPlace = false;
                  return;
               }

               if ((Boolean)this.chatFeedback.get()) {
                  this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §aRefilled off-hand with logs"), false);
               }

               offHand = this.mc.player.getOffHandStack();
            }

            if (this.isLogItem(offHand.getItem()) && offHand.getCount() > 0) {
               if ((Boolean)this.autoRotate.get()) {
                  this.rotateToBlock(placePos);
               }

               if (this.mc.interactionManager != null) {
                  this.mc.interactionManager.interactBlock(this.mc.player, Hand.OFF_HAND, new BlockHitResult(Vec3d.ofCenter(placePos), Direction.UP, placePos, false));
                  this.mc.player.swingHand(Hand.OFF_HAND);
                  if ((Boolean)this.chatFeedback.get()) {
                     this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §aPlaced log at " + String.valueOf(placePos)), false);
                  }

                  this.lastPlacedLog = placePos;
                  this.waitingToPlace = false;
               }

            } else {
               if ((Boolean)this.chatFeedback.get()) {
                  this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §cStill no logs in off-hand after refill attempt!"), false);
               }

               this.waitingToPlace = false;
            }
         }
      }
   }

   public void onLogPlaced(BlockPos pos) {
      this.lastPlacedLog = pos;
      if ((Boolean)this.chatFeedback.get()) {
         this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §aLog placed at " + String.valueOf(pos)), false);
      }

   }

   private boolean isLogItem(Item item) {
      return item == Items.OAK_LOG || item == Items.SPRUCE_LOG || item == Items.BIRCH_LOG || 
             item == Items.JUNGLE_LOG || item == Items.ACACIA_LOG || item == Items.DARK_OAK_LOG || 
             item == Items.MANGROVE_LOG || item == Items.CHERRY_LOG || item == Items.BAMBOO_BLOCK || 
             item == Items.CRIMSON_STEM || item == Items.WARPED_STEM;
   }
   
   // Get all supported log types for refill system
   private static final Item[] ALL_LOG_TYPES = {
      Items.OAK_LOG, Items.SPRUCE_LOG, Items.BIRCH_LOG, Items.JUNGLE_LOG,
      Items.ACACIA_LOG, Items.DARK_OAK_LOG, Items.MANGROVE_LOG, Items.CHERRY_LOG,
      Items.BAMBOO_BLOCK, Items.CRIMSON_STEM, Items.WARPED_STEM
   };

   private boolean refillOffHand() {
      ItemStack offHand = this.mc.player.getOffHandStack();
      Item targetLog = null;
      ClientPlayerEntity var10000;
      String var10001;
      if ((Boolean)this.chatFeedback.get()) {
         var10000 = this.mc.player;
         var10001 = String.valueOf(offHand.getItem());
         var10000.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §eRefill check - Off-hand: " + var10001 + " (count: " + offHand.getCount() + ")"), false);
      }

      int slot;
      if (this.isLogItem(offHand.getItem())) {
         targetLog = offHand.getItem();
         if ((Boolean)this.chatFeedback.get()) {
            this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §eLooking for same type: " + String.valueOf(targetLog)), false);
         }
      } else if ((Boolean)this.refillWithAnyLog.get()) {
         if ((Boolean)this.chatFeedback.get()) {
            this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §eLooking for any log type"), false);
         }

         for(Item logItem : ALL_LOG_TYPES) {
            int foundSlot = this.findItemSlot(logItem, true);
            if (foundSlot != -1) {
               targetLog = logItem;
               boolean showDebug = this.chatFeedback.get() && !this.quietMode.get();
               if (showDebug) {
                  String logName = logItem.toString().replace("minecraft:", "").replace("_log", "").replace("_stem", "").replace("_block", "").toUpperCase();
                  this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §eFound " + logName + " in slot " + foundSlot), false);
               }
               break;
            }
         }
      } else if (this.firstLogType != null && this.isLogItem(this.firstLogType)) {
         if ((Boolean)this.chatFeedback.get()) {
            this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §eLooking for first log type: " + String.valueOf(this.firstLogType)), false);
         }

         slot = this.findItemSlot(this.firstLogType, true);
         if (slot != -1) {
            targetLog = this.firstLogType;
            if ((Boolean)this.chatFeedback.get()) {
               var10000 = this.mc.player;
               var10001 = String.valueOf(this.firstLogType);
               var10000.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §eFound " + var10001 + " in slot " + slot), false);
            }
         } else if ((Boolean)this.chatFeedback.get()) {
            this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §cFirst log type not found in inventory"), false);
         }
      } else if ((Boolean)this.chatFeedback.get()) {
         this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §cFirst log type is null or not a log"), false);
      }

      if (targetLog == null) {
         if ((Boolean)this.chatFeedback.get()) {
            this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §cNo target log found"), false);
         }

         return false;
      } else {
         slot = this.findItemSlot(targetLog, true);
         if (slot == -1) {
            if ((Boolean)this.chatFeedback.get()) {
               this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §cTarget log not found in inventory: " + String.valueOf(targetLog)), false);
            }

            return false;
         } else {
            if ((Boolean)this.chatFeedback.get()) {
               this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §aFound target log in slot " + slot), false);
            }

            return this.moveLogsToOffHand(slot, targetLog);
         }
      }
   }

   private boolean moveLogsToOffHand(int logSlot, Item logItem) {
      try {
         ItemStack offHandStack = this.mc.player.getOffHandStack();
         int maxToMove = 64 - offHandStack.getCount();
         if (maxToMove <= 0) {
            return true;
         } else {
            ItemStack logStack = this.mc.player.getInventory().getStack(logSlot);
            int availableLogs = logStack.getCount();
            int logsToMove = Math.min(maxToMove, availableLogs);
            if (logsToMove <= 0) {
               return false;
            } else {
               if ((Boolean)this.chatFeedback.get()) {
                  String logTypeName = logItem.toString().replace("minecraft:", "").replace("_log", "").toUpperCase();
                  this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §aMoving " + logsToMove + " " + logTypeName + " logs to off-hand"), false);
               }

               int containerSlot;
               if (logSlot < 9) {
                  containerSlot = logSlot + 36;
               } else {
                  containerSlot = logSlot;
               }

               if ((Boolean)this.chatFeedback.get()) {
                  this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §eSwapping slot " + logSlot + " (container " + containerSlot + ") with off-hand"), false);
               }

               if ((Boolean)this.chatFeedback.get()) {
                  this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §eTrying to move logs to off-hand..."), false);
               }

               this.mc.interactionManager.clickSlot(this.mc.player.playerScreenHandler.syncId, containerSlot, 45, SlotActionType.SWAP, this.mc.player);
               this.mc.interactionManager.clickSlot(this.mc.player.playerScreenHandler.syncId, containerSlot, 0, SlotActionType.PICKUP, this.mc.player);
               this.mc.interactionManager.clickSlot(this.mc.player.playerScreenHandler.syncId, 45, 0, SlotActionType.PICKUP, this.mc.player);
               this.mc.interactionManager.clickSlot(this.mc.player.playerScreenHandler.syncId, containerSlot, 0, SlotActionType.QUICK_MOVE, this.mc.player);
               if ((Boolean)this.chatFeedback.get()) {
                  ItemStack newOffHand = this.mc.player.getOffHandStack();
                  ClientPlayerEntity var10000 = this.mc.player;
                  String var10001 = String.valueOf(offHandStack.getItem());
                  var10000.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §eOff-hand before: " + var10001 + " (count: " + offHandStack.getCount() + ")"), false);
                  var10000 = this.mc.player;
                  var10001 = String.valueOf(newOffHand.getItem());
                  var10000.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §eOff-hand after: " + var10001 + " (count: " + newOffHand.getCount() + ")"), false);
               }

               return true;
            }
         }
      } catch (Exception var10) {
         if ((Boolean)this.chatFeedback.get()) {
            this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §cError during refill: " + var10.getMessage()), false);
         }

         return false;
      }
   }

   private int findItemSlot(Item item, boolean excludeOffHand) {
      if ((Boolean)this.chatFeedback.get()) {
         ClientPlayerEntity var10000 = this.mc.player;
         String var10001 = String.valueOf(item);
         var10000.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §eSearching for item: " + var10001 + (excludeOffHand ? " (excluding off-hand)" : "")), false);
      }

      if (this.mc.player.getMainHandStack().getItem() == item) {
         if ((Boolean)this.chatFeedback.get()) {
            this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §eFound in main hand (slot " + this.mc.player.getInventory().getSelectedSlot() + ")"), false);
         }

         return this.mc.player.getInventory().getSelectedSlot();
      } else if (!excludeOffHand && this.mc.player.getOffHandStack().getItem() == item) {
         if ((Boolean)this.chatFeedback.get()) {
            this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §eFound in off-hand (slot 45)"), false);
         }

         return 45;
      } else {
         int i;
         for(i = 0; i < 9; ++i) {
            if (this.mc.player.getInventory().getStack(i).getItem() == item) {
               if ((Boolean)this.chatFeedback.get()) {
                  this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §eFound in hotbar slot " + i), false);
               }

               return i;
            }
         }

         for(i = 9; i < 36; ++i) {
            if (this.mc.player.getInventory().getStack(i).getItem() == item) {
               if ((Boolean)this.chatFeedback.get()) {
                  this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §eFound in inventory slot " + i), false);
               }

               return i;
            }
         }

         if ((Boolean)this.chatFeedback.get()) {
            this.mc.player.sendMessage(Text.literal("§8[§6Nora Tweaks§8] §cItem not found in inventory: " + String.valueOf(item)), false);
         }

         return -1;
      }
   }
}