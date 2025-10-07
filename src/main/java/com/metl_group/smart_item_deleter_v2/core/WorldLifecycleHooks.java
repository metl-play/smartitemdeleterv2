package com.metl_group.smart_item_deleter_v2.core;

import com.metl_group.smart_item_deleter_v2.ModMain;
import com.metl_group.smart_item_deleter_v2.persist.TrackedItemsData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.minecraft.server.level.ServerLevel;

@EventBusSubscriber(modid = ModMain.MOD_ID)
public final class WorldLifecycleHooks {
    private WorldLifecycleHooks() {}

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load e) {
        if (e.getLevel() instanceof ServerLevel sl) {
            // Warm-up SavedData to avoid first-time IO during a cleanup tick
            TrackedItemsData.get(sl);
        }
    }
}
