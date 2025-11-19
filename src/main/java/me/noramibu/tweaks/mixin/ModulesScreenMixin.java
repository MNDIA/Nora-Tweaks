package me.noramibu.tweaks.mixin;

import me.noramibu.tweaks.NoraTweaks;
import me.noramibu.tweaks.category.CustomCategory;
import me.noramibu.tweaks.category.CustomCategoryManager;
import me.noramibu.tweaks.category.SortOrder;
import me.noramibu.tweaks.events.CustomCategoriesChangedEvent;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.screens.ModulesScreen;
import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WWindow;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Mixin(targets = "meteordevelopment.meteorclient.gui.screens.ModulesScreen$WCategoryController", remap = false)
public abstract class ModulesScreenMixin extends WContainer {
    @Shadow @Final private ModulesScreen this$0;
    @Shadow public List<WWindow> windows;

    private final List<Cell<WWindow>> customCategoryCells = new ArrayList<>();

    private Cell<WWindow> createCustomCategory(CustomCategory category, List<Module> moduleList) {
        try {
            Field themeField = null;
            Class<?> currentClass = this$0.getClass();
            while (currentClass != null && themeField == null) {
                try {
                    themeField = currentClass.getDeclaredField("theme");
                } catch (NoSuchFieldException e) {
                    currentClass = currentClass.getSuperclass();
                }
            }

            if (themeField == null) {
                NoraTweaks.LOG.error("[NoraTweaks] The 'theme' field could not be found in the ModulesScreen class hierarchy.");
                return null;
            }

            themeField.setAccessible(true);
            GuiTheme theme = (GuiTheme) themeField.get(this$0);

            WWindow w = theme.window(category.name);
            w.id = "custom-" + category.name;
            w.padding = 0;
            w.spacing = 0;

            Cell<WWindow> cell = add(w);
            w.view.scrollOnlyWhenMouseOver = true;
            w.view.hasScrollBar = false;
            w.view.spacing = 0;

            if (moduleList == null || moduleList.isEmpty()) {
                w.add(theme.label("No modules.")).expandX();
            } else {
                List<Module> sortedModules = new java.util.ArrayList<>(moduleList);
                if (category.sortOrder == SortOrder.WEIGHT) {
                    sortedModules.sort(Comparator.comparingInt((Module m) -> CustomCategoryManager.getModuleWeight(m, category))
                        .thenComparing(m -> m.title));
                }
                else if (category.sortOrder == SortOrder.Z_TO_A) {
                    sortedModules.sort(Comparator.comparing((Module m) -> m.title).reversed());
                } else { // A_TO_Z or null
                    sortedModules.sort(Comparator.comparing(m -> m.title));
                }

                for (Module module : sortedModules) {
                    w.add(theme.module(module)).expandX();
                }
            }
            return cell;
        } catch (Exception e) {
            NoraTweaks.LOG.error("[NoraTweaks] Failed to create custom category window for '{}'.", category.name, e);
            return null;
        }
    }

    private void refreshCustomCategories() {
        for (Cell<WWindow> cell : customCategoryCells) {
            this.remove(cell);
            windows.remove(cell.widget());
        }
        customCategoryCells.clear();

        List<CustomCategory> customCategories = CustomCategoryManager.getCategories();

        for (CustomCategory category : customCategories) {
            List<Module> modules = CustomCategoryManager.getModules(category);
            Cell<WWindow> cell = createCustomCategory(category, modules);
            if (cell != null) {
                windows.add(cell.widget());
                customCategoryCells.add(cell);
            }
        }
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        MeteorClient.EVENT_BUS.subscribe(this);
        refreshCustomCategories();
    }

    @EventHandler
    private void onCategoriesChanged(CustomCategoriesChangedEvent event) {
        MinecraftClient.getInstance().execute(this::refreshCustomCategories);
    }
}
