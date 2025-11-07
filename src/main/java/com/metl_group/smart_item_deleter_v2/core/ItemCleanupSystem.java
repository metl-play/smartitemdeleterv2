package com.metl_group.smart_item_deleter_v2.core;

import com.metl_group.smart_item_deleter_v2.ModMain;
import com.metl_group.smart_item_deleter_v2.config.CleanupConfig;
import com.metl_group.smart_item_deleter_v2.persist.TrackedItem;
import com.metl_group.smart_item_deleter_v2.persist.TrackedItemsData;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class ItemCleanupSystem {
    // Next scheduled server tick to run the cleanup; replaces fixed modulo logic.
    private static long nextRunTick = 0L;

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
        for (ServerLevel level : server.getAllLevels()) {
            runCycle(level, nowMs);
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

        if (deleted > 0 && CleanupConfig.consoleDebugLogging) {
            ModLogger.info(level,
                    deleted,
                    analysis.eligibleCount(),
                    analysis.totalItems(),
                    analysis.configuredThreshold(),
                    analysis.minAgeUsedMs(),
                    analysis.deletePercentageUsed(),
                    analysis.excessCount(),
                    force);
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

        if (!force && candidates.size() <= threshold) {
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
                    Math.max(0, candidates.size() - configuredThreshold),
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

        var predicate = PolicyEngine.filterPredicate();
        List<ItemEntity> eligible = candidates.stream()
                .filter(ie -> {
                    TrackedItem ti = snapshot.get(ie.getUUID());
                    long firstSeen = ti != null ? ti.firstSeenMs() : nowMs;
                    return (nowMs - firstSeen) >= minAge;
                })
                .filter(predicate)
                .sorted(Comparator.comparingLong(ie -> {
                    TrackedItem ti = snapshot.get(ie.getUUID());
                    return ti != null ? ti.firstSeenMs() : nowMs;
                }))
                .collect(Collectors.toCollection(ArrayList::new));

        int excess = Math.max(0, candidates.size() - configuredThreshold);
        int quota = (int) Math.floor(eligible.size() * (pct / 100.0));
        int toDelete = force ? eligible.size() : Math.min(Math.max(0, candidates.size() - threshold), quota);
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

    // Collect all item entities in the level. Bounding box is expanded beyond world border to be safe.
    private static List<ItemEntity> allItems(ServerLevel level) {
        AABB bb = new AABB(
                level.getWorldBorder().getMinX() - 1_000, level.getMinBuildHeight(),
                level.getWorldBorder().getMinZ() - 1_000,
                level.getWorldBorder().getMaxX() + 1_000, level.getMaxBuildHeight(),
                level.getWorldBorder().getMaxZ() + 1_000
        );
        return level.getEntitiesOfClass(ItemEntity.class, bb);
    }

    private static final class ModLogger {
        // Formats a concise summary line. Arguments are positional on purpose to avoid string building in the hot path.
        static void info(ServerLevel level, Object... args) {
            level.getServer().sendSystemMessage(
                    net.minecraft.network.chat.Component.literal(
                            "[smart_item_deleter_v2] " + String.format(
                                    java.util.Locale.ROOT,
                                    "Cleanup: removed %d of %d eligible (total=%d, threshold=%d, minAge=%dms, pct=%d%%, excess=%d, forced=%s)",
                                    args
                            )
                    )
            );
        }
    }
}
