package me.noramibu.tweaks.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;


public class MobCheckerCommand extends Command {
	private static final int DEFAULT_RANGE = 64;

	public MobCheckerCommand() {
		super("mobchecker", "Count nearby mobs. Usage: .mobchecker [range] [filter]");
	}

	@Override
	public void build(LiteralArgumentBuilder<CommandSource> builder) {
		builder.executes(ctx -> {
			runCheck(DEFAULT_RANGE, null);
			return SINGLE_SUCCESS;
		});

		builder.then(argument("range", IntegerArgumentType.integer(1, 1024)).executes(ctx -> {
			int range = IntegerArgumentType.getInteger(ctx, "range");
			runCheck(range, null);
			return SINGLE_SUCCESS;
		}).then(argument("filter", StringArgumentType.word()).executes(ctx -> {
			int range = IntegerArgumentType.getInteger(ctx, "range");
			String filter = StringArgumentType.getString(ctx, "filter");
			runCheck(range, emptyToNull(filter));
			return SINGLE_SUCCESS;
		})));

		builder.then(argument("filter", StringArgumentType.word()).executes(ctx -> {
			String filter = StringArgumentType.getString(ctx, "filter");
			runCheck(DEFAULT_RANGE, emptyToNull(filter));
			return SINGLE_SUCCESS;
		}));
	}

	private static String emptyToNull(String s) {
		return s == null || s.isEmpty() ? null : s;
	}

	private void runCheck(int range, String mobTypeFilter) {
		if (mc.player == null || mc.world == null) return;

		Map<String, Integer> entityCounts = new HashMap<>();
		final int rangeSq = range * range;
		final String filterLower = (mobTypeFilter == null || mobTypeFilter.isEmpty()) ? null : mobTypeFilter.toLowerCase();

		//? if >=1.21.10 {
		Vec3d center = mc.player.getEntityPos();
		//?} else
		/*Vec3d center = mc.player.getPos();*/
		Box box = Box.of(center, range * 2.0, range * 2.0, range * 2.0);

		mc.world.getOtherEntities(null, box, entity -> true).forEach(entity -> {
			if (entity.squaredDistanceTo(mc.player) > (double) rangeSq) return;
			if (filterLower != null) {
				Identifier entityTypeId = EntityType.getId(entity.getType());
				if (entityTypeId == null || !entityTypeId.getPath().toLowerCase().contains(filterLower)) return;
			}

			String name = entity.getType().getName().getString();
			if (entity instanceof PassiveEntity && ((PassiveEntity) entity).isBaby()) name += " (Baby)";
			entityCounts.merge(name, 1, Integer::sum);
		});

		if (entityCounts.isEmpty()) {
			if (mobTypeFilter != null && !mobTypeFilter.isEmpty()) info("No '" + mobTypeFilter + "' found within " + range + " blocks.");
			else info("No mobs found within " + range + " blocks.");
			return;
		}

		if (mobTypeFilter != null && !mobTypeFilter.isEmpty()) info("Found matching '" + mobTypeFilter + "' within " + range + " blocks:");
		else info("Mobs found within " + range + " blocks:");

		entityCounts.entrySet().stream()
			.sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
			.forEach(e -> info("- " + e.getKey() + ": " + e.getValue()));

		if (entityCounts.size() > 1) {
			int totalSum = entityCounts.values().stream().mapToInt(Integer::intValue).sum();
			info("Total: " + totalSum);
		}
	}
}
