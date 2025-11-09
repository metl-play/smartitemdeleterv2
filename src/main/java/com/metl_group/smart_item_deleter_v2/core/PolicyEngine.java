package com.metl_group.smart_item_deleter_v2.core;

import com.metl_group.smart_item_deleter_v2.config.CleanupConfig;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
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
    public static Predicate<ItemEntity> filterPredicate() {
        return ie -> {
            if (isProtectedByName(ie)) return false;

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
                    String key = id.toString();
                    return matchesItemId(key, s);
                }
            });

            return switch (CleanupConfig.filterMode) {
                case BLACKLIST -> !listed; // allowed to delete when NOT listed
                case WHITELIST -> listed;  // allowed to delete when listed
            };
        };
}

    private static boolean matchesItemId(String key, String pattern) {
        if (!containsWildcards(pattern)) {
            return key.equals(pattern);
        }
        return wildcardMatch(key, pattern);
    }

    private static boolean containsWildcards(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '*' || c == '?') {
                return true;
            }
        }
        return false;
    }

    private static boolean wildcardMatch(String text, String pattern) {
        int textIndex = 0;
        int patternIndex = 0;
        int starPatternIndex = -1;
        int starTextIndex = -1;

        while (textIndex < text.length()) {
            if (patternIndex < pattern.length()) {
                char pc = pattern.charAt(patternIndex);
                char tc = text.charAt(textIndex);

                if (pc == '?' || pc == tc) {
                    patternIndex++;
                    textIndex++;
                    continue;
                }

                if (pc == '*') {
                    starPatternIndex = patternIndex++;
                    starTextIndex = textIndex;
                    continue;
                }
            }

            if (starPatternIndex != -1) {
                patternIndex = starPatternIndex + 1;
                textIndex = ++starTextIndex;
                continue;
            }

            return false;
        }

        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
            patternIndex++;
        }

        return patternIndex == pattern.length();
    }
}
