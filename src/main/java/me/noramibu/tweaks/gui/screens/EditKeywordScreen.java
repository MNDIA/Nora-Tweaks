package me.noramibu.tweaks.gui.screens;

import me.noramibu.tweaks.utils.Keyword;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;

import java.util.function.Consumer;

public class EditKeywordScreen extends WindowScreen {
    private final Keyword keyword;
    private final Consumer<Keyword> onSave;

    public EditKeywordScreen(GuiTheme theme, Keyword keyword, Consumer<Keyword> onSave) {
        super(theme, keyword == null ? "Add Keyword" : "Edit Keyword");
        this.keyword = keyword == null ? new Keyword("", false, false, false) : keyword;
        this.onSave = onSave;
    }

    @Override
    public void initWidgets() {
        WTable table = add(theme.table()).expandX().widget();

        // Name
        table.add(theme.label("Name:"));
        WTextBox nameBox = table.add(theme.textBox(this.keyword.name)).expandX().widget();
        nameBox.setFocused(true);
        table.row();

        // Settings
        table.add(theme.label("Case Sensitive:"));
        WCheckbox caseSensitiveBox = table.add(theme.checkbox(this.keyword.caseSensitive)).widget();
        table.row();
        table.add(theme.label("Whole Word:"));
        WCheckbox wholeWordBox = table.add(theme.checkbox(this.keyword.wholeWord)).widget();
        table.row();
        table.add(theme.label("Use Regex:"));
        WCheckbox useRegexBox = table.add(theme.checkbox(this.keyword.useRegex)).widget();
        table.row();

        // Save
        WButton saveButton = table.add(theme.button("Save")).expandX().widget();
        saveButton.action = () -> {
            this.keyword.name = nameBox.get();
            this.keyword.caseSensitive = caseSensitiveBox.checked;
            this.keyword.wholeWord = wholeWordBox.checked;
            this.keyword.useRegex = useRegexBox.checked;
            this.keyword.compilePattern();
            onSave.accept(this.keyword);
            onClose();
        };
    }
}
