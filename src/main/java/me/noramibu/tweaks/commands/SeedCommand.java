/*
 * This code partially adapted from Meteor Rejects
 * Original source: https://github.com/AntiCope/meteor-rejects/
 * Credit: Meteor Rejects contributors
 * If Meteor Rejects gets updated, adapted features will get removed.
 */
package me.noramibu.tweaks.commands;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import cubitect.Cubiomes;
import me.noramibu.tweaks.utils.Seeds;
import me.noramibu.tweaks.utils.Seeds.Seed;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.command.CommandSource;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.Arrays;
import java.util.Locale;

public class SeedCommand extends Command {
    private static final SimpleCommandExceptionType NO_SEED = new SimpleCommandExceptionType(Text.literal("No seed for current world saved."));
    private static final SimpleCommandExceptionType INVALID_VERSION = new SimpleCommandExceptionType(Text.literal("Unknown Minecraft version."));

    public SeedCommand() {
        super("seed", "Get or set the seed for the current world.");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.executes(ctx -> {
            Seed seed = Seeds.get().getSeed();
            if (seed == null) throw NO_SEED.create();
            info(seed.toText());
            return SINGLE_SUCCESS;
        });

        builder.then(literal("list").executes(ctx -> {
            Seeds.get().seeds.forEach((name, storedSeed) -> {
                if (storedSeed == null) return;
                MutableText text = Text.literal(name + " ");
                text.append(storedSeed.toText());
                info(text);
            });
            return SINGLE_SUCCESS;
        }));

        builder.then(literal("delete").executes(ctx -> {
            Seed seed = Seeds.get().getSeed();
            if (seed != null) {
                MutableText text = Text.literal("Deleted ");
                text.append(seed.toText());
                info(text);
            }
            Seeds.get().removeSeed(Utils.getWorldName());
            return SINGLE_SUCCESS;
        }));

        builder.then(argument("seed", StringArgumentType.string()).executes(ctx -> {
            Seeds.get().setSeed(StringArgumentType.getString(ctx, "seed"));
            return SINGLE_SUCCESS;
        }));

        builder.then(
            argument("seed", StringArgumentType.string())
                .then(argument("version", StringArgumentType.word())
                    .suggests((ctx, builder1) -> CommandSource.suggestMatching(
                        Arrays.asList("1.21.10", Cubiomes.MCVersion.MC_1_21_WD.name().toLowerCase(Locale.ROOT)),
                        builder1
                    ))
                    .executes(ctx -> {
                        Seeds.get().setSeed(
                            StringArgumentType.getString(ctx, "seed"),
                            parseVersion(StringArgumentType.getString(ctx, "version"))
                        );
                        return SINGLE_SUCCESS;
                    }))
        );
    }

    private static Cubiomes.MCVersion parseVersion(String input) throws CommandSyntaxException {
        Cubiomes.MCVersion version = null;
        try {
            version = Cubiomes.MCVersion.valueOf(input.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            version = me.noramibu.tweaks.utils.Seeds.resolveForPublic(input);
        }
        if (version == null) throw INVALID_VERSION.create();
        return version;
    }
}

