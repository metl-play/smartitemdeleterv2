package com.metl_group.smart_item_deleter_v2.core;

import com.metl_group.smart_item_deleter_v2.config.CleanupConfig;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

public final class PolicyEngine {
    private PolicyEngine(){}

    /** Protect named items via data components (1.21+). */
    public static boolean isProtectedByName(ItemEntity ie) {
        ItemStack stack = ie.getItem();
        return CleanupConfig.protectNamedItems && stack.has(DataComponents.CUSTOM_NAME);
    }

    /** Protect items close to players (configurable radius). */
    public static boolean isProtectedByPlayerRadius(ServerLevel level, ItemEntity ie) {
        int r = CleanupConfig.playerSafeRadius;
        if (r <= 0) return false;
        return !level.getEntitiesOfClass(net.minecraft.world.entity.player.Player.class,
                ie.getBoundingBox().inflate(r)).isEmpty();
    }

    /**
     * Build a stable item key.
     * For 1.21+ we avoid raw NBT (moved to data components). A simple, stable key is the registry ID.
     * If you later want to distinguish stacks by components, you can append a lightweight hash derived from components.
     */
    public static String itemKey(ItemStack stack) {
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id.toString();
    }

    /** Build the predicate that decides whether an ItemEntity is eligible for deletion. */
    public static Predicate<ItemEntity> filterPredicate(ServerLevel level) {
        return ie -> {
            if (isProtectedByName(ie)) return false;
            if (isProtectedByPlayerRadius(level, ie)) return false;

            ItemStack stack = ie.getItem();
            Item item = stack.getItem();
            ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);

            boolean listed = CleanupConfig.filterList.stream().anyMatch(s -> {
                if (s.startsWith("#")) {
                    // Tag check via ItemStack#is to avoid deprecated holder API
                    ResourceLocation tagId = ResourceLocation.tryParse(s.substring(1));
                    if (tagId == null) return false;
                    TagKey<Item> tag = TagKey.create(net.minecraft.core.registries.Registries.ITEM, tagId);
                    return stack.is(tag);
                } else {
                    return s.equals(id.toString());
                }
            });

            return switch (CleanupConfig.filterMode) {
                case BLACKLIST -> !listed; // allowed to delete when NOT listed
                case WHITELIST -> listed;  // allowed to delete when listed
            };
        };
    }
}
