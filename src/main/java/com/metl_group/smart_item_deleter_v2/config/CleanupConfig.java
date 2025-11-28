package com.metl_group.smart_item_deleter_v2.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.fml.config.ModConfig;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CleanupConfig {
    public enum FilterMode { BLACKLIST, WHITELIST }

    public static final ModConfigSpec SERVER_SPEC;
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    public static int scanIntervalTicks;
    public static int entityCountThreshold;
    public static int deletePercentage;
    public static long minItemAgeMs;
    public static boolean protectNamedItems;
    public static FilterMode filterMode;
    public static java.util.List<String> filterList = java.util.List.of();
    public static boolean jitterEnabled;
    public static int scanJitterTicks; // e.g. 2
    public static boolean consoleDebugLogging;
    public static ModConfig serverConfig;
    private static final ModConfigSpec.BooleanValue CFG_JITTER_ENABLED;
    private static final ModConfigSpec.IntValue CFG_SCAN_JITTER;
    private static final ModConfigSpec.BooleanValue CFG_CONSOLE_DEBUG_LOGGING;

    private static final ModConfigSpec.IntValue CFG_SCAN_INTERVAL;
    private static final ModConfigSpec.IntValue CFG_THRESHOLD;
    private static final ModConfigSpec.IntValue CFG_DELETE_PERCENT;
    private static final ModConfigSpec.LongValue CFG_MIN_AGE_MS;
    private static final ModConfigSpec.BooleanValue CFG_PROTECT_NAMED;
    private static final ModConfigSpec.EnumValue<FilterMode> CFG_FILTER_MODE;
    private static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> CFG_FILTER_LIST;

    static {
        B.push("general");
        CFG_SCAN_INTERVAL = B.defineInRange("scanIntervalTicks", 20, 1, 20_000);
        CFG_SCAN_JITTER    = B.defineInRange("scanJitterTicks", 2, 0, 40);
        CFG_JITTER_ENABLED = B.define("scanJitterEnabled", true);
        CFG_THRESHOLD      = B.defineInRange("entityCountThreshold", 400, 1, 10_000);
        CFG_DELETE_PERCENT = B.defineInRange("deletePercentage", 80, 0, 100);
        CFG_MIN_AGE_MS     = B.defineInRange("minItemAgeMs", 15_000L, 0L, 86_400_000L);
        B.pop();

        B.push("safety");
        CFG_PROTECT_NAMED  = B.define("protectNamedItems", true);
        B.pop();

        B.push("logging");
        CFG_CONSOLE_DEBUG_LOGGING = B.comment("When false, the mod will not print cleanup summaries to the server console.")
                .define("consoleDebugLogging", false);
        B.pop();

        B.push("filter");
        CFG_FILTER_MODE = B.defineEnum("filterMode", FilterMode.BLACKLIST);
        CFG_FILTER_LIST = B.comment("List of item IDs or tags (#tag)")
            .defineList(
                "filterList",
                java.util.List.of("minecraft:nether_star", "#modid:valuable"),
                () -> "minecraft:air",              // seed for new entries in UI
                obj -> obj instanceof String s && !s.isBlank()
            );
        B.pop();

        SERVER_SPEC = B.build();
    }

    public static void bake() {
        scanIntervalTicks    = CFG_SCAN_INTERVAL.get();
        scanJitterTicks      = CFG_SCAN_JITTER.get();
        jitterEnabled        = CFG_JITTER_ENABLED.get();
        entityCountThreshold = CFG_THRESHOLD.get();
        deletePercentage     = CFG_DELETE_PERCENT.get();
        minItemAgeMs         = CFG_MIN_AGE_MS.get();
        protectNamedItems    = CFG_PROTECT_NAMED.get();
        consoleDebugLogging  = CFG_CONSOLE_DEBUG_LOGGING.get();
        filterMode           = CFG_FILTER_MODE.get();
        filterList           = CFG_FILTER_LIST.get().stream().map(Object::toString).toList();
        if (filterList.isEmpty()) {
            filterList = java.util.List.of();
        }
    }

    private CleanupConfig() {}

    public static Map<String, ConfigBinding> discoverBindings() {
        Map<String, ConfigBinding> bindings = new LinkedHashMap<>();

        for (Field field : CleanupConfig.class.getDeclaredFields()) {
            if (!ModConfigSpec.ConfigValue.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                field.setAccessible(true);
                ModConfigSpec.ConfigValue<?> value = (ModConfigSpec.ConfigValue<?>) field.get(null);
                if (value == null) {
                    continue;
                }
                List<String> path = value.getPath();
                ModConfigSpec.ValueSpec spec = value.getSpec();
                bindings.put(String.join(".", path), new ConfigBinding(value, spec));
            } catch (IllegalAccessException ignored) {
                // Skip inaccessible entries
            }
        }

        return bindings.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll);
    }

    public static void trackConfig(ModConfig config) {
        if (config.getSpec() == SERVER_SPEC) {
            serverConfig = config;
        }
    }

    public record ConfigBinding(ModConfigSpec.ConfigValue<?> value, ModConfigSpec.ValueSpec spec) {}
}
