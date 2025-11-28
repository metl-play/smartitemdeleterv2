package com.metl_group.smart_item_deleter_v2.command;

import com.metl_group.smart_item_deleter_v2.config.CleanupConfig;
import com.metl_group.smart_item_deleter_v2.core.ItemCleanupSystem;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static com.metl_group.smart_item_deleter_v2.core.ItemCleanupSystem.RunSummary;

public final class CleanupCommands {
    private CleanupCommands(){}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("cleanup")
                .requires(CleanupCommands::canExecute)
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
                .then(Commands.literal("config")
                        .then(Commands.literal("list")
                                .executes(CleanupCommands::executeConfigList)
                        )
                        .then(Commands.argument("key", StringArgumentType.word())
                                .suggests(CleanupCommands::suggestConfigKeys)
                                .executes(CleanupCommands::executeConfigGet)
                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                        .suggests(CleanupCommands::suggestConfigValue)
                                        .executes(CleanupCommands::executeConfigSet)
                                )
                        )
                )
        );
    }

    private static boolean canExecute(CommandSourceStack source) {
        return source.hasPermission(2);
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

    private static int executeConfigList(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        Map<String, CleanupConfig.ConfigBinding> bindings = new LinkedHashMap<>(CleanupConfig.discoverBindings());

        if (bindings.isEmpty()) {
            source.sendFailure(Component.literal("No dynamic config entries were discovered."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Cleanup config options (current values):"), false);
        bindings.forEach((path, binding) -> source.sendSuccess(
                () -> Component.literal(String.format(Locale.ROOT, "- %s = %s", path, renderValue(binding.value().get()))),
                false));
        return bindings.size();
    }

    private static int executeConfigGet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var source = ctx.getSource();
        String key = StringArgumentType.getString(ctx, "key");
        CleanupConfig.ConfigBinding binding = locateBinding(key);

        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "%s = %s", key, renderValue(binding.value().get()))), false);
        return 1;
    }

    private static int executeConfigSet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        var source = ctx.getSource();
        String key = StringArgumentType.getString(ctx, "key");
        String rawValue = StringArgumentType.getString(ctx, "value");

        CleanupConfig.ConfigBinding binding = locateBinding(key);
        Object parsed = parseValue(rawValue, binding);

        if (!binding.spec().test(parsed)) {
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherParseException()
                    .create(String.format(Locale.ROOT, "Value '%s' rejected by config constraints for %s", rawValue, key));
        }

        Object old = binding.value().get();
        setBindingValue(binding, parsed);
        binding.value().save();

        CleanupConfig.bake();

        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Updated %s: %s -> %s", key, renderValue(old), renderValue(binding.value().get()))), true);
        return 1;
    }

    private static Object parseValue(String rawValue, CleanupConfig.ConfigBinding binding) throws CommandSyntaxException {
        Object current = binding.value().get();
        try {
            if (current instanceof Boolean) {
                if (rawValue.equalsIgnoreCase("true") || rawValue.equalsIgnoreCase("false")) {
                    return Boolean.parseBoolean(rawValue);
                }
                throw new IllegalArgumentException("expected boolean");
            }
            if (current instanceof Integer) {
                return Integer.parseInt(rawValue);
            }
            if (current instanceof Long) {
                return Long.parseLong(rawValue);
            }
            if (current instanceof Double) {
                return Double.parseDouble(rawValue);
            }
            if (current instanceof Enum<?> e) {
                return Enum.valueOf(e.getDeclaringClass(), rawValue.toUpperCase(Locale.ROOT));
            }
            if (current instanceof List<?> ignored) {
                return Arrays.stream(rawValue.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .toList();
            }
        } catch (IllegalArgumentException ex) {
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherParseException()
                    .create(String.format(Locale.ROOT, "Could not parse value '%s' for %s", rawValue, String.join(".", binding.value().getPath())));
        }

        return rawValue;
    }

    @SuppressWarnings("unchecked")
    private static void setBindingValue(CleanupConfig.ConfigBinding binding, Object parsed) {
        ((ModConfigSpec.ConfigValue<Object>) binding.value()).set(parsed);
    }

    private static CompletableFuture<Suggestions> suggestConfigKeys(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        CleanupConfig.discoverBindings().keySet().forEach(builder::suggest);
        return builder.buildFuture();
    }

    private static CompletableFuture<Suggestions> suggestConfigValue(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        String key = StringArgumentType.getString(ctx, "key");
        CleanupConfig.ConfigBinding binding = CleanupConfig.discoverBindings().get(key);
        if (binding == null) {
            return builder.buildFuture();
        }

        Object current = binding.value().get();
        if (current instanceof Boolean) {
            builder.suggest("true");
            builder.suggest("false");
        } else if (current instanceof Enum<?> e) {
            for (Enum<?> constant : e.getDeclaringClass().getEnumConstants()) {
                builder.suggest(constant.name().toLowerCase(Locale.ROOT));
            }
        } else if (current instanceof List<?> list && !list.isEmpty()) {
            builder.suggest(renderValue(list));
        } else {
            builder.suggest(current.toString());
        }
        return builder.buildFuture();
    }

    private static CleanupConfig.ConfigBinding locateBinding(String key) throws CommandSyntaxException {
        CleanupConfig.ConfigBinding binding = CleanupConfig.discoverBindings().get(key);
        if (binding == null) {
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument().create();
        }
        return binding;
    }

    private static String renderValue(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(Objects::toString).reduce((a, b) -> a + "," + b).orElse("");
        }
        return String.valueOf(value);
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
    }
}
