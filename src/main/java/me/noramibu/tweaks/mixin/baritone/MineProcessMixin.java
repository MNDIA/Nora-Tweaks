package me.noramibu.tweaks.mixin.baritone;

import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.pathing.movement.CalculationContext;
import me.noramibu.tweaks.modules.OreSim;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;

/**
 * Mixin for Baritone's MineProcess to inject OreSim's simulated ore positions.
 * Targets baritone.process.MineProcess for:
 * - baritone-unoptimized: field 'knownOreLocations', method 'rescan'
 * - baritone-meteor: field 'a', method 'a(List, CalculationContext)'
 * For baritone-api see MineProcessMixinApi.
**/
@Mixin(targets = "baritone.process.MineProcess", remap = false, priority = 2000)
public class MineProcessMixin {

    // Cache the field reference for performance
    @Unique
    private static Field cachedField;

    @Unique
    private static boolean fieldLookupAttempted = false;

    /**
     * Intercepts the rescan method to replace ore locations with OreSim's simulated positions.
     */
    @Inject(
        method = {"rescan", "a(Ljava/util/List;Lbaritone/pathing/movement/CalculationContext;)V"},
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private void onRescan(List<BlockPos> already, CalculationContext context, CallbackInfo ci) {
        OreSim oreSim = Modules.get().get(OreSim.class);
        if (oreSim == null || !oreSim.baritone()) {
            return;
        }

        // Set the knownOreLocations field using reflection
        if (setKnownOreLocations(this, oreSim.oreGoals)) {
            ci.cancel();
        }
    }

    /**
     * Sets the knownOreLocations field using reflection.
     */
    @Unique
    private static boolean setKnownOreLocations(Object instance, List<BlockPos> locations) {
        try {
            Field field = getKnownOreLocationsField(instance.getClass());
            if (field != null) {
                field.set(instance, locations);
                return true;
            }
        } catch (Exception e) {
            // Silently fail - mixin just won't work
        }
        return false;
    }

    @Unique
    private static Field getKnownOreLocationsField(Class<?> clazz) {
        if (fieldLookupAttempted) {
            return cachedField;
        }

        fieldLookupAttempted = true;

        // Try non-obfuscated name first (baritone-unoptimized)
        try {
            cachedField = clazz.getDeclaredField("knownOreLocations");
            cachedField.setAccessible(true);
            return cachedField;
        } catch (NoSuchFieldException ignored) {}

        // For baritone-meteor, iterate fields in declaration order.
        Field firstListBlockPosField = null;
        for (Field field : clazz.getDeclaredFields()) {
            if (List.class.isAssignableFrom(field.getType())) {
                Type genericType = field.getGenericType();
                if (genericType instanceof ParameterizedType) {
                    Type[] typeArgs = ((ParameterizedType) genericType).getActualTypeArguments();
                    if (typeArgs.length > 0) {
                        String typeName = typeArgs[0].getTypeName();
                        if (typeName.contains("BlockPos") || typeName.contains("class_2338")) {
                            if (firstListBlockPosField == null) {
                                firstListBlockPosField = field;
                                firstListBlockPosField.setAccessible(true);
                            }
                            break;
                        }
                    }
                }
            }
        }

        cachedField = firstListBlockPosField;
        return cachedField;
    }

    @Redirect(
        method = "*",
        at = @At(value = "INVOKE", target = "Lbaritone/api/utils/BlockOptionalMetaLookup;has(Lnet/minecraft/block/BlockState;)Z"),
        remap = false,
        require = 0
    )
    private static boolean onPruneStream(BlockOptionalMetaLookup instance, BlockState blockState) {
        OreSim oreSim = Modules.get().get(OreSim.class);
        if (oreSim == null || !oreSim.baritone()) {
            return instance.has(blockState);
        }
        // When OreSim baritone is active, accept any non-air block
        return !blockState.isAir();
    }
}
