package me.noramibu.tweaks.utils;

import net.minecraft.nbt.NbtCompound;

import java.util.regex.Pattern;

public class Keyword {
    public String name;
    public boolean caseSensitive;
    public boolean wholeWord;
    public boolean useRegex;
    public transient Pattern pattern;

    public Keyword(String name, boolean caseSensitive, boolean wholeWord, boolean useRegex) {
        this.name = name;
        this.caseSensitive = caseSensitive;
        this.wholeWord = wholeWord;
        this.useRegex = useRegex;
        compilePattern();
    }

    public void compilePattern() {
        if (name == null || name.isEmpty()) {
            pattern = null;
            return;
        }

        try {
            if (useRegex) {
                int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
                pattern = Pattern.compile(name, flags);
            } else {
                String patternString = Pattern.quote(name);
                if (wholeWord) {
                    patternString = "\\b" + patternString + "\\b";
                }
                int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
                pattern = Pattern.compile(patternString, flags);
            }
        } catch (Exception e) {
            pattern = null;
        }
    }

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("name", name);
        nbt.putBoolean("caseSensitive", caseSensitive);
        nbt.putBoolean("wholeWord", wholeWord);
        nbt.putBoolean("useRegex", useRegex);
        return nbt;
    }

    public static Keyword fromNbt(NbtCompound nbt) {
        //? if >=1.21.5 {
        Keyword keyword = new Keyword(
            nbt.getString("name").orElse(""),
            nbt.getBoolean("caseSensitive").orElse(false),
            nbt.getBoolean("wholeWord").orElse(false),
            nbt.getBoolean("useRegex").orElse(false)
        );
        //?} else {
        /*Keyword keyword = new Keyword(
            nbt.getString("name"),
            nbt.getBoolean("caseSensitive"),
            nbt.getBoolean("wholeWord"),
            nbt.getBoolean("useRegex")
        );
        */
        //?}
        keyword.compilePattern();
        return keyword;
    }
}
