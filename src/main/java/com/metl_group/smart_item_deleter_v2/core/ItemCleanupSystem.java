package com.metl_group.smart_item_deleter_v2.core;

import com.metl_group.smart_item_deleter_v2.ModMain;
import com.metl_group.smart_item_deleter_v2.config.CleanupConfig;
import com.metl_group.smart_item_deleter_v2.persist.TrackedItem;
import com.metl_group.smart_item_deleter_v2.persist.TrackedItemsData;
import com.metl_group.smart_item_deleter_v2.util.LogFiles;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class ItemCleanupSystem {
    // Next scheduled server tick to run the cleanup; replaces fixed modulo logic.
    private static long nextRunTick = 0L;
    private static final DateTimeFormatter CLEANUP_LOG_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ItemCleanupSystem(){}

    @SubscribeEvent
    public static void onServerTick(final ServerTickEvent.Post e) {
        final MinecraftServer server = e.getServer();
        final long nowTick = server.getTickCount();

        // Initialize schedule on first tick
        if (nextRunTick == 0L) {
            nextRunTick = nowTick + computeDelayTicks();
            return;
        }

        // Not yet time to run
        if (nowTick < nextRunTick) return;

        // Run once for all levels
        final long nowMs = nowTick * 50L; // ms approx.
        List<RunSummary> summaries = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            summaries.add(runCycle(level, nowMs));
        }

        int totalDeleted = summaries.stream().mapToInt(RunSummary::deleted).sum();
        if (totalDeleted > 0) {
            int totalAttempted = summaries.stream().mapToInt(RunSummary::attemptedDeletes).sum();
            logCleanupDetails(summaries, totalDeleted, totalAttempted);
            if (CleanupConfig.consoleDebugLogging) {
                emitConsoleSummary(server, totalDeleted, totalAttempted, summaries.size());
            }
        }

        // Schedule next run with slight jitter to avoid synchronized spikes with other mods
        nextRunTick = nowTick + computeDelayTicks();
    }

    /**
     * Computes the delay until the next run in ticks: base +/- jitter (clamped to >= 1).
     * Jitter is controlled by the config so server owners can tune or disable the spread.
     */
    private static int computeDelayTicks() {
        int base = Math.max(1, CleanupConfig.scanIntervalTicks);
        if (!CleanupConfig.jitterEnabled) {
            return base;
        }

        int jitter = Math.max(0, CleanupConfig.scanJitterTicks);
        if (jitter <= 0) {
            return base;
        }

        // Prevent jitter from pushing delay below 1 tick.
        jitter = Math.min(jitter, Math.max(0, base - 1));
        int offset = ThreadLocalRandom.current().nextInt(-jitter, jitter + 1);
        int delay = base + offset;
        return Math.max(1, delay);
    }

    /**
     * Runs a single cleanup cycle for the given level.
     * Behavior:
     *  - Only act if the total item count exceeds the threshold.
     *  - Track items (firstSeen/lastSeen) only when above threshold.
     *  - Build the eligible list (age + policy filter).
     *  - Sort by age (oldest first) to protect the newest items.
     *  - Delete up to min(excess, percentage-of-eligible).
     */
    public static RunSummary runCycle(ServerLevel level, long nowMs) {
        return runCycle(level, nowMs, false);
    }

    public static RunSummary runCycle(ServerLevel level, long nowMs, boolean force) {
        Analysis analysis = analyze(level, nowMs, true, force);
        TrackedItemsData data = TrackedItemsData.get(level);

        int deleted = 0;
        for (ItemEntity ie : analysis.deleteOrder()) {
            if (!ie.isRemoved() && ie.isAlive()) {
                ie.discard();
                data.remove(ie.getUUID());
                deleted++;
            }
        }

        return new RunSummary(analysis, deleted);
    }

    public static Analysis analyze(ServerLevel level, long nowMs) {
        return analyze(level, nowMs, false, false);
    }

    private static Analysis analyze(ServerLevel level, long nowMs, boolean mutate, boolean force) {
        TrackedItemsData data = TrackedItemsData.get(level);
        Map<UUID, TrackedItem> working = new HashMap<>(data.map());

        List<ItemEntity> candidates = new ArrayList<>();
        Set<UUID> liveIds = new HashSet<>();
        for (ItemEntity ie : allItems(level)) {
            if (PolicyEngine.isProtectedByName(ie)) {
                if (mutate) {
                    data.remove(ie.getUUID());
                }
                continue;
            }
            candidates.add(ie);
            liveIds.add(ie.getUUID());
        }

        int configuredThreshold = CleanupConfig.entityCountThreshold;
        int threshold = force ? 0 : configuredThreshold;
        long configuredMinAge = CleanupConfig.minItemAgeMs;
        long minAge = force ? 0L : configuredMinAge;
        int configuredPct = CleanupConfig.deletePercentage;
        int pct = force ? 100 : configuredPct;

        var predicate = PolicyEngine.filterPredicate();
        List<ItemEntity> filteredCandidates = candidates.stream()
                .filter(predicate)
                .collect(Collectors.toCollection(ArrayList::new));
        int filteredCount = filteredCandidates.size();

        if (!force && filteredCount <= threshold) {
            if (mutate) {
                data.clearIfNotEmpty();
            }
            return new Analysis(
                    level,
                    nowMs,
                    candidates.size(),
                    configuredThreshold,
                    threshold,
                    configuredMinAge,
                    minAge,
                    configuredPct,
                    pct,
                    0,
                    0,
                    Math.max(0, filteredCount - configuredThreshold),
                    force,
                    List.of(),
                    List.of(),
                    Map.copyOf(data.map())
            );
        }

        if (mutate) {
            data.retainOnly(liveIds);
            working = new HashMap<>(data.map());
        } else {
            working.keySet().retainAll(liveIds);
        }

        Map<UUID, TrackedItem> snapshot = new HashMap<>();
        for (ItemEntity ie : candidates) {
            UUID id = ie.getUUID();
            TrackedItem old = working.get(id);
            String key = PolicyEngine.itemKey(ie.getItem());
            TrackedItem nu = (old == null)
                    ? new TrackedItem(id, level.dimension().location(), ie.position(), key, nowMs, nowMs)
                    : new TrackedItem(id, old.dimension(), ie.position(), key, old.firstSeenMs(), nowMs);

            snapshot.put(id, nu);
            if (mutate) {
                data.putOrUpdate(nu);
            } else {
                working.put(id, nu);
            }
        }

        List<ItemEntity> eligible = filteredCandidates.stream()
                .filter(ie -> {
                    TrackedItem ti = snapshot.get(ie.getUUID());
                    long firstSeen = ti != null ? ti.firstSeenMs() : nowMs;
                    return (nowMs - firstSeen) >= minAge;
                })
                .sorted(Comparator.comparingLong(ie -> {
                    TrackedItem ti = snapshot.get(ie.getUUID());
                    return ti != null ? ti.firstSeenMs() : nowMs;
                }))
                .collect(Collectors.toCollection(ArrayList::new));

        int excess = Math.max(0, filteredCount - configuredThreshold);
        int quota = (int) Math.floor(eligible.size() * (pct / 100.0));
        int toDelete = force ? eligible.size() : Math.min(Math.max(0, filteredCount - threshold), quota);
        if (force && pct < 100) {
            toDelete = Math.min(eligible.size(), (int) Math.floor(eligible.size() * (pct / 100.0)));
        }

        List<ItemEntity> deletionOrder = new ArrayList<>(eligible.subList(0, Math.min(toDelete, eligible.size())));

        return new Analysis(
                level,
                nowMs,
                candidates.size(),
                configuredThreshold,
                threshold,
                configuredMinAge,
                minAge,
                configuredPct,
                pct,
                eligible.size(),
                deletionOrder.size(),
                excess,
                force,
                List.copyOf(eligible),
                List.copyOf(deletionOrder),
                Map.copyOf(snapshot)
        );
    }

    public record Analysis(
            ServerLevel level,
            long timestampMs,
            int totalItems,
            int configuredThreshold,
            int thresholdUsed,
            long configuredMinAgeMs,
            long minAgeUsedMs,
            int configuredDeletePercentage,
            int deletePercentageUsed,
            int eligibleCount,
            int scheduledDeletes,
            int excessCount,
            boolean forced,
            List<ItemEntity> eligibleItems,
            List<ItemEntity> deleteOrder,
            Map<UUID, TrackedItem> trackingSnapshot
    ) {
        public int trackedCount() {
            return trackingSnapshot.size();
        }

        public long firstSeenMs(ItemEntity ie) {
            TrackedItem ti = trackingSnapshot.get(ie.getUUID());
            return ti != null ? ti.firstSeenMs() : timestampMs;
        }

        public long ageMs(ItemEntity ie) {
            long first = firstSeenMs(ie);
            return Math.max(0L, timestampMs - first);
        }
    }

    public record RunSummary(Analysis analysis, int deleted) {
        public int attemptedDeletes() {
            return analysis.deleteOrder().size();
        }
    }

    /**
     * Collect all loaded item entities.
     *
     * <p>Using {@link ServerLevel#getAllEntities()} avoids accessing protected chunk internals while still
     * respecting the server's view of loaded entities (no chunk loading or world-spanning searches).
     */
    private static List<ItemEntity> allItems(ServerLevel level) {
        List<ItemEntity> items = new ArrayList<>();
        for (var entity : level.getAllEntities()) {
            if (entity instanceof ItemEntity ie) {
                items.add(ie);
            }
        }
        return items;
    }

    private static void emitConsoleSummary(MinecraftServer server, int totalDeleted, int totalAttempted, int levelCount) {
        server.sendSystemMessage(
                net.minecraft.network.chat.Component.literal(
                        "[smart_item_deleter_v2] " + String.format(
                                Locale.ROOT,
                                "Cleanup job: removed %d/%d eligible across %d dimension(s).",
                                totalDeleted,
                                totalAttempted,
                                levelCount
                        )
                )
        );
    }

    private static void logCleanupDetails(List<RunSummary> summaries, int totalDeleted, int totalAttempted) {
        if (summaries.isEmpty()) {
            return;
        }

        Path logPath = LogFiles.cleanupLogPath();
        String timestamp = LocalDateTime.now().format(CLEANUP_LOG_TIMESTAMP);
        String lineSeparator = System.lineSeparator();

        StringBuilder payload = new StringBuilder();
        payload.append('[')
                .append(timestamp)
                .append("] Cleanup job: removed ")
                .append(totalDeleted)
                .append('/')
                .append(totalAttempted)
                .append(" eligible across ")
                .append(summaries.size())
                .append(" dimension(s).")
                .append(lineSeparator);

        for (RunSummary summary : summaries) {
            if (summary.deleted() <= 0) {
                continue;
            }
            Analysis analysis = summary.analysis();
            long oldestAttemptedAgeMs = analysis.deleteOrder().stream()
                    .mapToLong(analysis::ageMs)
                    .max()
                    .orElse(0L);
            payload.append(String.format(
                    Locale.ROOT,
                    " - %s: removed %d of %d eligible (total=%d, threshold=%d, minAge=%dms, pct=%d%%, excess=%d, forced=%s, oldestAttemptedAgeMs=%d)",
                    analysis.level().dimension().location(),
                    summary.deleted(),
                    analysis.eligibleCount(),
                    analysis.totalItems(),
                    analysis.configuredThreshold(),
                    analysis.minAgeUsedMs(),
                    analysis.deletePercentageUsed(),
                    analysis.excessCount(),
                    analysis.forced(),
                    oldestAttemptedAgeMs))
                .append(lineSeparator);
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
        } catch (IOException ignored) {
            // Avoid spamming console if log write fails.
        }
    }
}
