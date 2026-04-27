package me.noramibu.tweaks.gui.screens;

import me.noramibu.tweaks.category.CustomCategory;
import me.noramibu.tweaks.category.CustomCategoryManager;
import me.noramibu.tweaks.events.CustomCategoriesChangedEvent;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.Minecraft;

public class AddToCategoryScreen extends WindowScreen {
    private final Module module;
    private WTable table;

    public AddToCategoryScreen(GuiTheme theme, Module module) {
        super(theme, "Add to Category - " + module.title);
        this.module = module;
    }

    @Override
    protected void init() {
        super.init();
        MeteorClient.EVENT_BUS.subscribe(this);
    }

    @Override
    public void initWidgets() {
        table = add(new WTable()).expandX().widget();
        buildTable();
    }

    private void buildTable() {
        table.clear();
        for (CustomCategory category : CustomCategoryManager.getCategories()) {
            table.add(theme.label(category.name)).expandX();
            WCheckbox checkbox = table.add(theme.checkbox(CustomCategoryManager.isModuleInCategory(module, category))).widget();
            checkbox.action = () -> CustomCategoryManager.toggleModuleAssignment(module, category);
            table.row();
        }
    }

    @EventHandler
    @SuppressWarnings("unused")
    private void onCategoriesChanged(CustomCategoriesChangedEvent event) {
        Minecraft.getInstance().execute(this::buildTable);
    }

    @Override
    public void onClose() {
        MeteorClient.EVENT_BUS.unsubscribe(this);
        super.onClose();
    }
}
