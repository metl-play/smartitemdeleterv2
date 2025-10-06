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
     * Jitter is fixed to +/- 2 ticks to spread load slightly without affecting responsiveness.
     */
    private static int computeDelayTicks() {
        int base = Math.max(1, CleanupConfig.scanIntervalTicks);
        // keep jitter small and safe
        int jitter = Math.min(2, Math.max(0, base - 1));
        int offset = java.util.concurrent.ThreadLocalRandom.current().nextInt(-jitter, jitter + 1);
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
    public static void runCycle(ServerLevel level, long nowMs) {
        List<ItemEntity> items = allItems(level);

        // Only proceed if we exceed the threshold. This also prevents "aging" while under threshold.
        int total = items.size();
        int threshold = CleanupConfig.entityCountThreshold;
        if (total <= threshold) {
            return;
        }

        // Persistent tracking state
        TrackedItemsData data = TrackedItemsData.get(level);

        // Update tracking (firstSeen/lastSeen) only while above threshold.
        for (ItemEntity ie : items) {
            UUID id = ie.getUUID();
            TrackedItem old = data.map().get(id);
            String key = PolicyEngine.itemKey(ie.getItem());
            TrackedItem nu = (old == null)
                    ? new TrackedItem(id, level.dimension().location(), ie.position(), key, nowMs, nowMs)
                    : new TrackedItem(id, old.dimension(), ie.position(), key, old.firstSeenMs(), nowMs);
            data.putOrUpdate(nu);
        }

        // Build eligible list: old enough + matches filter policy
        var eligible = items.stream()
                .filter(ie -> {
                    TrackedItem ti = data.map().get(ie.getUUID());
                    long firstSeen = (ti != null ? ti.firstSeenMs() : nowMs);
                    return (nowMs - firstSeen) >= CleanupConfig.minItemAgeMs;
                })
                .filter(PolicyEngine.filterPredicate(level))
                .sorted(Comparator.comparingLong((ItemEntity ie) -> {
                    TrackedItem ti = data.map().get(ie.getUUID());
                    return (ti != null ? ti.firstSeenMs() : nowMs);
                }))
                .collect(Collectors.toCollection(ArrayList::new));

        // Sort oldest first (ascending by firstSeenMs) so that the newest items remain safe

        // Determine deletion counts:
        //  - "excess": how far we are over the threshold
        //  - "quota": percentage of eligible items we are allowed to delete
        //  - final deletion count: min(excess, quota)
        int excess = Math.max(0, total - threshold);
        int pct = Math.max(0, Math.min(100, CleanupConfig.deletePercentage));
        int quota = (int) Math.floor(eligible.size() * (pct / 100.0));
        int toDelete = Math.min(excess, quota);

        int deleted = 0;
        for (int i = 0; i < eligible.size() && deleted < toDelete; i++) {
            ItemEntity ie = eligible.get(i);
            if (!ie.isRemoved() && ie.isAlive()) {
                ie.discard();
                data.remove(ie.getUUID());
                deleted++;
            }
        }

        if (deleted > 0) {
            ModLogger.info(level,
                    deleted, eligible.size(), total, threshold, CleanupConfig.minItemAgeMs, pct, excess);
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
                                    "Cleanup: removed %d of %d eligible (total=%d, threshold=%d, minAge=%dms, pct=%d%%, excess=%d)",
                                    args
                            )
                    )
            );
        }
    }
}
