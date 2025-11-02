package com.metl_group.smart_item_deleter_v2.command;

import com.metl_group.smart_item_deleter_v2.core.ItemCleanupSystem;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static com.metl_group.smart_item_deleter_v2.core.ItemCleanupSystem.RunSummary;

public final class CleanupCommands {
    private CleanupCommands(){}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("cleanup")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("now")
                        .executes(ctx -> executeNow(ctx, false))
                        .then(Commands.literal("force")
                                .executes(ctx -> executeNow(ctx, true))
                        )
                )
                .then(Commands.literal("stats")
                        .executes(CleanupCommands::executeStats)
                )
                .then(Commands.literal("dryrun")
                        .executes(CleanupCommands::executeDryRun)
                )
        );
    }

    private static int executeNow(CommandContext<CommandSourceStack> ctx, boolean force) {
        var source = ctx.getSource();
        var server = source.getServer();
        long nowMs = server.getTickCount() * 50L;

        int levels = 0;
        int attempted = 0;
        int removed = 0;
        List<String> perLevel = new ArrayList<>();

        for (ServerLevel level : server.getAllLevels()) {
            RunSummary summary = ItemCleanupSystem.runCycle(level, nowMs, force);
            ItemCleanupSystem.Analysis analysis = summary.analysis();
            levels++;
            attempted += summary.attemptedDeletes();
            removed += summary.deleted();

            perLevel.add(String.format(Locale.ROOT,
                    "- %s: total=%d tracked=%d eligible=%d scheduled=%d removed=%d",
                    level.dimension().location(),
                    analysis.totalItems(),
                    analysis.trackedCount(),
                    analysis.eligibleCount(),
                    analysis.scheduledDeletes(),
                    summary.deleted()));
        }

        Component header = Component.literal(String.format(Locale.ROOT,
                "Cleanup executed (force=%s): removed %d/%d items across %d dimension(s).",
                force,
                removed,
                attempted,
                levels));

        String detail = String.join("\n", perLevel);
        Component full = detail.isEmpty() ? header : header.copy().append("\n").append(detail);

        source.sendSuccess(() -> full, true);
        return removed;
    }

    private static int executeStats(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        var server = source.getServer();
        long nowMs = server.getTickCount() * 50L;

        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(String.format(Locale.ROOT, "Cleanup stats @ %d ms:", nowMs)));

        List<ItemCleanupSystem.Analysis> analyses = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            analyses.add(ItemCleanupSystem.analyze(level, nowMs));
        }
        analyses.sort(Comparator.comparing(a -> a.level().dimension().location().toString()));

        for (ItemCleanupSystem.Analysis analysis : analyses) {
            String line = String.format(Locale.ROOT,
                    "- %s: total=%d tracked=%d threshold=%d excess=%d eligible=%d scheduled=%d",
                    analysis.level().dimension().location(),
                    analysis.totalItems(),
                    analysis.trackedCount(),
                    analysis.configuredThreshold(),
                    analysis.excessCount(),
                    analysis.eligibleCount(),
                    analysis.scheduledDeletes());
            lines.add(Component.literal(line));
        }

        lines.forEach(line -> source.sendSuccess(() -> line, false));
        return 1;
    }

    private static int executeDryRun(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        var server = source.getServer();
        long nowMs = server.getTickCount() * 50L;

        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Cleanup dry-run @ %d ms:", nowMs)), false);

        for (ServerLevel level : server.getAllLevels()) {
            ItemCleanupSystem.Analysis analysis = ItemCleanupSystem.analyze(level, nowMs);

            if (analysis.deleteOrder().isEmpty()) {
                source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "- %s: nothing to delete (total=%d, threshold=%d)",
                        level.dimension().location(),
                        analysis.totalItems(),
                        analysis.configuredThreshold())), false);
                continue;
            }

            List<ItemEntityPreview> previews = analysis.deleteOrder().stream()
                    .limit(10)
                    .map(ie -> new ItemEntityPreview(ie, analysis.ageMs(ie)))
                    .toList();

            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "- %s: would delete %d/%d eligible items (total=%d, excess=%d)",
                    level.dimension().location(),
                    analysis.scheduledDeletes(),
                    analysis.eligibleCount(),
                    analysis.totalItems(),
                    analysis.excessCount())), false);

            for (ItemEntityPreview preview : previews) {
                source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "    • %dx %s @ (%.1f, %.1f, %.1f) age=%.1fs",
                        preview.count(),
                        preview.name(),
                        preview.x(),
                        preview.y(),
                        preview.z(),
                        preview.ageMs() / 1000.0
                )), false);
            }

            int remaining = analysis.deleteOrder().size() - previews.size();
            if (remaining > 0) {
                source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "    … and %d more item(s)", remaining)), false);
            }
        }

        return 1;
    }

    private record ItemEntityPreview(String name, int count, double x, double y, double z, long ageMs) {
        ItemEntityPreview(net.minecraft.world.entity.item.ItemEntity entity, long ageMs) {
            this(entity.getItem().getHoverName().getString(),
                    entity.getItem().getCount(),
                    entity.getX(),
                    entity.getY(),
                    entity.getZ(),
                    ageMs);
        }

        public long ageMs() {
            return ageMs;
        }
    }
}
