package me.noramibu.tweaks.category;

public class ModuleConfig {
    public String moduleName;
    public int weight = 100;

    // For GSON
    public ModuleConfig() {}

    public ModuleConfig(String moduleName) {
        this.moduleName = moduleName;
    }
}
