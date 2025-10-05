package com.metl_group.smart_item_deleter_v2.persist;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

public record TrackedItem(
        UUID uuid,
        ResourceLocation dimension,
        Vec3 pos,
        String itemKey,
        long firstSeenMs,
        long lastSeenMs
) {}
