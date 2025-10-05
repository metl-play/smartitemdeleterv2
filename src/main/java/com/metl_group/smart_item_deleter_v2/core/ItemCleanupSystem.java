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

@EventBusSubscriber(modid = ModMain.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class ItemCleanupSystem {
    private static int tickCounter = 0;

    private ItemCleanupSystem(){}

    @SubscribeEvent
    public static void onServerTick(final ServerTickEvent.Post e) {
        tickCounter++;
        if (tickCounter % Math.max(1, CleanupConfig.scanIntervalTicks) != 0) return;

        MinecraftServer server = e.getServer();
        long now = server.getTickCount() * 50L; // ms approx.

        for (ServerLevel level : server.getAllLevels()) {
            runCycle(level, now);
        }
    }

    public static void runCycle(ServerLevel level, long nowMs) {
        List<ItemEntity> items = allItems(level);

        if (items.size() < CleanupConfig.entityCountThreshold) {
            return;
        }

        TrackedItemsData data = TrackedItemsData.get(level);

        for (ItemEntity ie : items) {
            UUID id = ie.getUUID();
            TrackedItem old = data.map().get(id);
            String key = PolicyEngine.itemKey(ie.getItem());
            TrackedItem nu = (old == null)
                    ? new TrackedItem(id, level.dimension().location(), ie.position(), key, nowMs, nowMs)
                    : new TrackedItem(id, old.dimension(), ie.position(), key, old.firstSeenMs(), nowMs);
            data.putOrUpdate(nu);
        }

        var canDelete = items.stream()
                .filter(ie -> (nowMs - data.map().getOrDefault(ie.getUUID(),
                        new TrackedItem(ie.getUUID(), level.dimension().location(), ie.position(),
                                PolicyEngine.itemKey(ie.getItem()), nowMs, nowMs)).firstSeenMs())
                        >= CleanupConfig.minItemAgeMs)
                .filter(PolicyEngine.filterPredicate(level)).sorted(Comparator.comparingLong(
                        (ItemEntity ie) -> data.map().get(ie.getUUID()).firstSeenMs()).reversed()).collect(Collectors.toCollection(ArrayList::new));

        int max = CleanupConfig.maxDeletesPerCycle;
        int deleted = 0;

        for (ItemEntity ie : canDelete) {
            if (deleted >= max) break;
            if (!ie.isRemoved() && ie.isAlive()) {
                ie.discard();
                data.remove(ie.getUUID());
                deleted++;
            }
        }

        if (deleted > 0) {
            ModLogger.info(level,
                    deleted, canDelete.size(), items.size(), CleanupConfig.entityCountThreshold, CleanupConfig.minItemAgeMs);
        }
    }

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
        static void info(ServerLevel level, Object... args) {
            level.getServer().sendSystemMessage(
                    net.minecraft.network.chat.Component.literal("[smart_item_deleter_v2] " + String.format(java.util.Locale.ROOT, "Cleanup: %d von %d Items entfernt (%d total, threshold=%d, minAge=%dms)", args))
            );
        }
    }
}
