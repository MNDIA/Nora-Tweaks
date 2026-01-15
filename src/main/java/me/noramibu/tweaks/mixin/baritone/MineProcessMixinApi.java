package me.noramibu.tweaks.mixin.baritone;

import baritone.api.utils.BlockOptionalMetaLookup;
import me.noramibu.tweaks.modules.OreSim;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
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
 * Mixin for baritone-api's MineProcess.
 * - baritone-api 1.21.11: baritone.em
 * - baritone-api 1.21.8/1.21.7: baritone.ek
**/
@Pseudo
@Mixin(targets = {"baritone.em", "baritone.ek"}, remap = false, priority = 2000)
public class MineProcessMixinApi {
    @Unique
    private static Field cachedField;

    @Unique
    private static boolean fieldLookupAttempted = false;

    @Unique
    private static boolean isMineProcess = false;

    @Unique
    private static boolean isMineProcessChecked = false;

    @Inject(
        method = "a(Ljava/util/List;Lbaritone/ca;)V",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private void onRescan(CallbackInfo ci) {
        if (!checkIsMineProcess()) {
            return;
        }

        OreSim oreSim = Modules.get().get(OreSim.class);
        if (oreSim == null || !oreSim.baritone()) {
            return;
        }

        if (setKnownOreLocations(this, oreSim.oreGoals)) {
            ci.cancel();
        }
    }

    @Unique
    private boolean checkIsMineProcess() {
        if (isMineProcessChecked) {
            return isMineProcess;
        }
        isMineProcessChecked = true;

        for (Class<?> iface : this.getClass().getInterfaces()) {
            if (iface.getName().contains("MineProcess") || iface.getSimpleName().equals("cw")) {
                isMineProcess = true;
                return true;
            }
        }

        Class<?> parent = this.getClass().getSuperclass();
        while (parent != null && parent != Object.class) {
            for (Class<?> iface : parent.getInterfaces()) {
                if (iface.getName().contains("MineProcess")) {
                    isMineProcess = true;
                    return true;
                }
            }
            parent = parent.getSuperclass();
        }

        return false;
    }

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

    /**
     * Gets the first List<BlockPos> field (knownOreLocations), caching the result.
     */
    @Unique
    private static Field getKnownOreLocationsField(Class<?> clazz) {
        if (fieldLookupAttempted) {
            return cachedField;
        }

        fieldLookupAttempted = true;

        // For baritone-api (baritone.em), iterate fields in declaration order.
        // The knownOreLocations field is the FIRST List<BlockPos> field declared.
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

    /**
     * Redirects the BlockOptionalMetaLookup.has() check to be more permissive when OreSim is active.
     * Uses wildcard '*' to match any method containing has() call.
     */
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
