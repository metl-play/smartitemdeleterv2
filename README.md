# 🧹 Smart Item Deleter v2
*A lightweight, intelligent item cleanup system for NeoForge 1.21.1*

---

## 📘 Overview

**Smart Item Deleter v2** is a server-side optimization mod designed to automatically clean up dropped item entities when the item count exceeds a defined threshold.  
It tracks items individually to ensure fair, efficient, and safe removal — deleting only excess, old, and unimportant drops without disrupting normal gameplay.

> ✅ Supports NeoForge 21.1+  
> ⚙️ Designed for Create-based and heavily modded survival servers  
> 💾 Low overhead, deterministic cleanup cycles

---

## ⚙️ Configuration (for server owners)

Configuration file:
```
config/smart_item_deleter_v2-common.toml
```

| Option | Type | Default | Description |
|--------|------|----------|-------------|
| `entityCountThreshold` | `int` | `200` | Number of dropped item entities required before cleanup activates. |
| `minItemAgeMs` | `long` | `15000` | Minimum age (in milliseconds) before an item becomes eligible for deletion. Prevents immediate removal of new drops. |
| `scanIntervalTicks` | `int` | `20` | How often (in ticks) the system scans the world for items (20 ticks = 1 second). |
| `scanJitterEnabled` | `boolean` | `true` | Adds small random offset (±`scanJitterTicks`) to interval to reduce server tick spikes when multiple mods act simultaneously. |
| `scanJitterTicks` | `int` | `2` | Maximum jitter added/subtracted from each cleanup cycle’s timing. |
| `deletePercentage` | `int` | `90` | Percentage of eligible items to delete each cycle (0–100). Protects the newest items even when threshold is exceeded. |
| `whitelistMode` | `boolean` | `false` | Toggles whitelist (true) or blacklist (false) filtering behavior. |
| `filteredItems` | `list` | `[]` | A list of item registry IDs (`minecraft:stone`, `create:cogwheel`, etc.) that define which items are protected (blacklist) or targeted (whitelist). |

### Example:
```toml
entityCountThreshold = 250
minItemAgeMs = 15000
scanIntervalTicks = 20
deletePercentage = 80
whitelistMode = false
filteredItems = ["minecraft:nether_star", "minecraft:diamond"]
```

This configuration means:
- Cleanup runs roughly every second.
- Only starts when >250 dropped items exist.
- Deletes 80% of all items older than 15 seconds (prioritizing the oldest).
- Diamonds and Nether Stars will never be deleted.

---

## 🧠 Technical Details (for developers and maintainers)

### Core Behavior
- Items are tracked in `TrackedItemsData`, using persistent per-level storage.
- Each item stores:
    - `UUID`
    - `dimension`
    - `firstSeenMs` (time first detected)
    - `lastSeenMs` (time last confirmed)
- Cleanup only occurs **when the total item count exceeds the configured threshold**.
- Items are eligible if:
    1. Their age ≥ `minItemAgeMs`
    2. They pass the current policy filter (blacklist/whitelist mode)
- When `protectNamedItems` is enabled, items with custom names are ignored entirely — they do not count toward the threshold and
  are never deleted.

### Deletion Logic
- All eligible items are sorted **oldest first** (ascending by `firstSeenMs`).
- The number of deletions per cycle is:
  ```
  deletions = min(excess_items, eligible_items * (deletePercentage / 100))
  ```
- This ensures:
    - The server maintains stable tick times.
    - Recent player drops are preserved.
    - Automated machines that constantly spill items are kept clean.

### Jitter (scan desynchronization)
- The system uses a randomized interval of:
  ```
  nextInterval = scanIntervalTicks ± scanJitterTicks
  ```
  to avoid simultaneous heavy-tick bursts when multiple mods or systems run periodic updates.

### Code Structure
| Package | Purpose |
|----------|----------|
| `core/` | Cleanup logic, ticking, filtering, and execution |
| `persist/` | Persistent tracking data (`SavedData`) for per-world storage |
| `config/` | Configuration spec and loading |
| `command/` | Optional `/cleanup` admin command for manual triggering |

### Commands
| Command | Description |
|----------|-------------|
| `/cleanup run` | Forces a cleanup cycle manually. |
| `/cleanup status` | (Planned) Displays tracked item count, eligible items, and current thresholds. |

---

## 💡 Future Plans
- Provide in-game feedback via action bar or server console only
- Expose metrics to `/cleanup status` or a scoreboard-compatible data point

---

## 📜 License
MIT License — freely usable and modifiable.  
Please credit `Metl_Play` if redistributed.  
Would be appreciated if I am mentioned in modpacks.

---

## 🧩 Credits
- **Developer:** Metl_Play
- **Minecraft:** 1.21.1 (NeoForge)
- **Mappings:** Parchment mappings
- **Libraries:** NeoForge API, standard Java collections

> “It’s not just a cleaner mod — it’s a smarter janitor.” 🧠🧹
