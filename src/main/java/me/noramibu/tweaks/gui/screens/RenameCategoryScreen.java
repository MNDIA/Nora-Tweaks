package me.noramibu.tweaks.gui.screens;

import me.noramibu.tweaks.category.CustomCategory;
import me.noramibu.tweaks.category.CustomCategoryManager;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;

public class RenameCategoryScreen extends WindowScreen {
    private final CustomCategory category;

    public RenameCategoryScreen(GuiTheme theme, CustomCategory category) {
        super(theme, "Rename Category");
        this.category = category;
    }

    @Override
    public void initWidgets() {
        WTable table = add(new WTable()).expandX().widget();

        // Text box
        WTextBox nameBox = table.add(theme.textBox(category.name)).expandX().widget();
        nameBox.setFocused(true);

        table.row();

        // Rename button
        WButton renameButton = table.add(theme.button("Rename")).expandX().widget();
        renameButton.action = () -> {
            String newName = nameBox.get().trim();
            if (!newName.isEmpty() && !newName.equals(category.name)) {
                CustomCategoryManager.renameCategory(category, newName);
                this.close();
            }
        };
    }
}
