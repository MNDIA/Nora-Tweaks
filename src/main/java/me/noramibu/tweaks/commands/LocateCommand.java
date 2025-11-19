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
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import cubitect.Cubiomes;
import cubitect.Cubiomes.Pos;
import me.noramibu.tweaks.utils.WorldGenUtils;
import me.noramibu.tweaks.utils.Seeds;
import me.noramibu.tweaks.utils.Seeds.Seed;
import meteordevelopment.meteorclient.commands.Command;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import net.minecraft.command.CommandSource;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Arrays;
import java.util.Locale;

public class LocateCommand extends Command {
    private static final DynamicCommandExceptionType NOT_FOUND = new DynamicCommandExceptionType(o -> {
        if (o instanceof Cubiomes.StructureType type) {
            return Text.literal(String.format("%s not found.", Utils.nameToTitle(type.toString().replace('_', '-'))));
        }
        return Text.literal("Not found.");
    });
    private static final DynamicCommandExceptionType INVALID_FEATURE = new DynamicCommandExceptionType(o ->
        Text.literal(String.format("%s is not a valid feature.", o))
    );

    public LocateCommand() {
        super("seed-locate", "Locate structures using the stored seed.", "seed-loc");
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSource> builder) {
        builder.then(literal("feature")
            .then(argument("feature", StringArgumentType.word())
                .suggests((ctx, builder1) -> CommandSource.suggestMatching(
                    Arrays.stream(Cubiomes.StructureType.values())
                        .map(type -> type.name().toLowerCase(Locale.ROOT)),
                    builder1
                ))
                .executes(ctx -> {
                    Cubiomes.StructureType feature = parseFeature(StringArgumentType.getString(ctx, "feature"));
                if (mc.player == null) return SINGLE_SUCCESS;

                BlockPos playerPos = mc.player.getBlockPos();
                Seed seed = Seeds.get().getSeed();
                if (seed == null) throw NOT_FOUND.create(feature);

                Cubiomes.MCVersion cubiomesVersion = seed.version;
                Pos pos;
                if (cubiomesVersion != null) {
                    pos = Cubiomes.GetNearestStructure(feature, playerPos.getX(), playerPos.getZ(), seed.seed, cubiomesVersion);
                } else {
                    BlockPos fallback = WorldGenUtils.locateFeature(feature, playerPos);
                    if (fallback == null) throw NOT_FOUND.create(feature);
                    pos = new Pos();
                    pos.x = fallback.getX();
                    pos.z = fallback.getZ();
                }

                if (pos == null) throw NOT_FOUND.create(feature);

                int distance = (int) Math.hypot(pos.x - playerPos.getX(), pos.z - playerPos.getZ());
                MutableText text = Text.literal(String.format("%s located at ", Utils.nameToTitle(feature.toString().replace('_', '-'))));
                text.append(ChatUtils.formatCoords(new Vec3d(pos.x, 0, pos.z)));
                text.append(".");
                if (distance > 0) {
                    text.append(String.format(" (%d blocks away)", distance));
                }
                info(text);
                return SINGLE_SUCCESS;
            })));
    }

    // No mapping needed; Seeds stores Cubiomes.MCVersion directly

    private static Cubiomes.StructureType parseFeature(String input) throws CommandSyntaxException {
        for (Cubiomes.StructureType type : Cubiomes.StructureType.values()) {
            if (type.name().equalsIgnoreCase(input)) return type;
        }
        throw INVALID_FEATURE.create(input);
    }
}
