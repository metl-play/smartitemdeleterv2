package com.metl_group.smart_item_deleter_v2.persist;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class TrackedItemsData extends SavedData {
    public static final String DATA_NAME = "smart_item_deleter_v2_tracked_items";

    private final Map<UUID, TrackedItem> map = new HashMap<>();

    public TrackedItemsData() {}

    /** Accessor for this world's saved data instance. */
    public static TrackedItemsData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
            new SavedData.Factory<>(TrackedItemsData::new, TrackedItemsData::load),
            DATA_NAME
        );
    }

    /** Loader required by SavedDataStorage in 1.21.1 (lookup present even if unused here). */
    public static TrackedItemsData load(CompoundTag root, HolderLookup.Provider lookup) {
        TrackedItemsData data = new TrackedItemsData();
        ListTag list = root.getList("items", Tag.TAG_COMPOUND);
        for (Tag t : list) {
            CompoundTag c = (CompoundTag) t;
            UUID id = c.getUUID("uuid");
            String dim = c.getString("dim");
            double x = c.getDouble("x");
            double y = c.getDouble("y");
            double z = c.getDouble("z");
            String key = c.getString("itemKey");
            long first = c.getLong("first");
            long last = c.getLong("last");

            // Use ResourceLocation.parse for 1.21+
            ResourceLocation dimKey = ResourceLocation.parse(dim);
            Vec3 pos = new Vec3(x, y, z);

            data.map.put(id, new TrackedItem(id, dimKey, pos, key, first, last));
        }
        return data;
    }

    /** New save signature in 1.21.1 (lookup present even if unused). */
    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider lookup) {
        ListTag list = new ListTag();
        for (TrackedItem ti : map.values()) {
            CompoundTag c = new CompoundTag();
            c.putUUID("uuid", ti.uuid());
            c.putString("dim", ti.dimension().toString());
            c.putDouble("x", ti.pos().x);
            c.putDouble("y", ti.pos().y);
            c.putDouble("z", ti.pos().z);
            c.putString("itemKey", ti.itemKey());
            c.putLong("first", ti.firstSeenMs());
            c.putLong("last", ti.lastSeenMs());
            list.add(c);
        }
        root.put("items", list);
        return root;
    }

    // --- Mutators / Accessors ---

    public Map<UUID, TrackedItem> map() {
        return map;
    }

    /** Put or update a tracked item; marks data dirty for saving. */
    public void putOrUpdate(TrackedItem ti) {
        map.put(ti.uuid(), ti);
        setDirty();
    }

    /** Remove a tracked item; marks data dirty if present. */
    public void remove(UUID id) {
        if (map.remove(id) != null) {
            setDirty();
        }
    }
}
