package com.metl_group.smart_item_deleter_v2.core;

import com.metl_group.smart_item_deleter_v2.config.CleanupConfig;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
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
            var rules = CleanupConfig.compiledFilterRules;

            boolean listed = false;
            String key = null;
            for (CleanupConfig.FilterRule rule : rules) {
                switch (rule.kind) {
                    case TAG -> {
                        if (stack.is(rule.tag)) {
                            listed = true;
                        }
                    }
                    case EXACT_ID -> {
                        if (key == null) {
                            key = itemKey(stack);
                        }
                        if (key.equals(rule.pattern)) {
                            listed = true;
                        }
                    }
                    case WILDCARD_ID -> {
                        if (key == null) {
                            key = itemKey(stack);
                        }
                        if (wildcardMatch(key, rule.pattern)) {
                            listed = true;
                        }
                    }
                }
                if (listed) {
                    break;
                }
            }

            return switch (CleanupConfig.filterMode) {
                case BLACKLIST -> !listed; // allowed to delete when NOT listed
                case WHITELIST -> listed;  // allowed to delete when listed
            };
        };
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
