package com.metl_group.smart_item_deleter_v2.command;

import com.metl_group.smart_item_deleter_v2.config.CleanupConfig;
import com.metl_group.smart_item_deleter_v2.core.ItemCleanupSystem;
import com.metl_group.smart_item_deleter_v2.util.LogFiles;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static com.metl_group.smart_item_deleter_v2.core.ItemCleanupSystem.RunSummary;

public final class CleanupCommands {
    private CleanupCommands(){}
    private static final DateTimeFormatter STATS_LOG_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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
        List<String> logLines = new ArrayList<>();
        String header = String.format(Locale.ROOT, "Cleanup stats @ %d ms:", nowMs);
        lines.add(Component.literal(header));
        logLines.add(header);

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
            lines.add(Component.literal(line).withStyle(colorForDimension(analysis.level().dimension().location().toString())));
            logLines.add(line);
        }

        lines.forEach(line -> source.sendSuccess(() -> line, false));
        appendStatsLog(source, logLines);
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
            switch (current) {
                case Boolean b -> {
                    if (rawValue.equalsIgnoreCase("true") || rawValue.equalsIgnoreCase("false")) {
                        return Boolean.parseBoolean(rawValue);
                    }
                    throw new IllegalArgumentException("expected boolean");
                }
                case Integer i -> {
                    return Integer.parseInt(rawValue);
                }
                case Long l -> {
                    return Long.parseLong(rawValue);
                }
                case Double v -> {
                    return Double.parseDouble(rawValue);
                }
                case Enum<?> e -> {
                    return Enum.valueOf(e.getDeclaringClass(), rawValue.toUpperCase(Locale.ROOT));
                }
                case List<?> ignored -> {
                    return Arrays.stream(rawValue.split(","))
                            .map(String::trim)
                            .filter(s -> !s.isBlank())
                            .toList();
                }
                default -> {
                }
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
        switch (current) {
            case Boolean b -> {
                builder.suggest("true");
                builder.suggest("false");
            }
            case Enum<?> e -> {
                for (Enum<?> constant : e.getDeclaringClass().getEnumConstants()) {
                    builder.suggest(constant.name().toLowerCase(Locale.ROOT));
                }
            }
            case List<?> list when !list.isEmpty() -> builder.suggest(renderValue(list));
            default -> builder.suggest(current.toString());
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

    private static void appendStatsLog(CommandSourceStack source, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }

        Path logPath = LogFiles.statsLogPath();
        String timestamp = LocalDateTime.now().format(STATS_LOG_TIMESTAMP);
        String lineSeparator = System.lineSeparator();

        StringBuilder payload = new StringBuilder();
        payload.append('[').append(timestamp).append("] ").append(lines.getFirst()).append(lineSeparator);
        for (int i = 1; i < lines.size(); i++) {
            payload.append(lines.get(i)).append(lineSeparator);
        }
        payload.append(lineSeparator);

        try {
            Files.createDirectories(logPath.getParent());
            Files.writeString(
                    logPath,
                    payload.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException ex) {
            source.sendFailure(Component.literal("Failed to write cleanup stats log: " + ex.getMessage()));
        }
    }

    private static ChatFormatting colorForDimension(String dimensionId) {
        return switch (dimensionId) {
            case "minecraft:overworld" -> ChatFormatting.GREEN;
            case "minecraft:the_end" -> ChatFormatting.YELLOW;
            case "minecraft:the_nether" -> ChatFormatting.RED;
            default -> ChatFormatting.LIGHT_PURPLE;
        };
    }

}
