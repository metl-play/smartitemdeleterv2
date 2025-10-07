package com.metl_group.smart_item_deleter_v2.core;

import com.metl_group.smart_item_deleter_v2.ModMain;
import com.metl_group.smart_item_deleter_v2.config.CleanupConfig;
import com.metl_group.smart_item_deleter_v2.persist.TrackedItem;
import com.metl_group.smart_item_deleter_v2.persist.TrackedItemsData;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Server-side periodic item cleanup.
 * Hardening for async/hybrid servers:
 * - Skip when no players online
 * - Per-level: skip when no players in that level
 * - Scan only around players (view distance radius), not the whole world
 * - Do not broadcast messages when no players are online
 * - Ensure main-thread execution
 */
@EventBusSubscriber(modid = ModMain.MOD_ID, value = Dist.DEDICATED_SERVER)
public final class ItemCleanupSystem {
    private static long nextRunTick = 0L;

    private ItemCleanupSystem(){}

    @SubscribeEvent
    public static void onServerTick(final ServerTickEvent.Post e) {
        final MinecraftServer server = e.getServer();

        // Failsafe: ensure main-thread
        if (!server.isSameThread()) {
            server.execute(() -> onServerTick(e));
            return;
        }

        // If no players are online, do nothing. This avoids touching world state during async join.
        if (server.getPlayerCount() <= 0) return;

        final long nowTick = server.getTickCount();
        if (nextRunTick == 0L) { nextRunTick = nowTick + computeDelayTicks(); return; }
        if (nowTick < nextRunTick) return;

        final long nowMs = nowTick * 50L; // approx ms
        for (ServerLevel level : server.getAllLevels()) {
            // Skip empty dimensions; critical on hybrid/async join pipeline.
            if (level.players().isEmpty()) continue;
            runCycle(level, nowMs);
        }

        nextRunTick = nowTick + computeDelayTicks();
    }

    /** Computes next delay ticks: base +/- small jitter (clamped to >=1). */
    private static int computeDelayTicks() {
        int base = Math.max(1, CleanupConfig.scanIntervalTicks);
        int jitter = Math.min(2, Math.max(0, base - 1));
        int offset = java.util.concurrent.ThreadLocalRandom.current().nextInt(-jitter, jitter + 1);
        return Math.max(1, base + offset);
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
        // Collect only items near players in this level, not global world.
        List<ItemEntity> items = itemsNearPlayers(level);
        int total = items.size();

        int threshold = CleanupConfig.entityCountThreshold;
        if (total <= threshold) {
            return; // No tracking/aging while under threshold
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
                .collect(Collectors.toCollection(ArrayList::new));

        // Sort oldest first (ascending by firstSeenMs) so that the newest items remain safe
        eligible.sort(Comparator.comparingLong((ItemEntity ie) -> {
            TrackedItem ti = data.map().get(ie.getUUID());
            return (ti != null ? ti.firstSeenMs() : nowMs);
        }));

        // Determine deletion counts:
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

    /**
     * Collect item entities near players only.
     * Radius is derived from server view distance (in chunks), with a safe fallback.
     */
    private static List<ItemEntity> itemsNearPlayers(ServerLevel level) {
        // Try to read the server's configured view distance (chunks). Fallback to 10 chunks.
        int vdChunks;
        try {
            vdChunks = Math.max(2, level.getServer().getPlayerList().getViewDistance()); // usually in chunks
        } catch (Throwable t) {
            vdChunks = 10;
        }
        int blocks = vdChunks * 16 + 16; // a little extra margin

        // Use a set to avoid duplicates when player AABBs overlap.
        LinkedHashSet<ItemEntity> set = new LinkedHashSet<>();
        for (ServerPlayer p : level.players()) {
            AABB bb = new AABB(
                    p.getX() - blocks, level.getMinBuildHeight(), p.getZ() - blocks,
                    p.getX() + blocks, level.getMaxBuildHeight(), p.getZ() + blocks
            );
            set.addAll(level.getEntitiesOfClass(ItemEntity.class, bb));
        }
        return new ArrayList<>(set);
    }

    private static final class ModLogger {
        static void info(ServerLevel level, Object... args) {
            String line = String.format(java.util.Locale.ROOT,
                    "Cleanup: removed %d of %d eligible (total=%d, threshold=%d, minAge=%dms, pct=%d%%, excess=%d)",
                    args);
            // Always log to server console
            ModMain.LOGGER.info("[smart_item_deleter_v2] {}", line);

            // Only broadcast to the game if players exist (prevents net paths during async joins)
            if (!level.players().isEmpty()) {
                level.getServer().sendSystemMessage(
                        net.minecraft.network.chat.Component.literal("[smart_item_deleter_v2] " + line)
                );
            }
        }
    }
}
