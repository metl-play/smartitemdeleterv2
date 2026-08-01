# 🧹 Smart Item Deleter v2
*A lightweight, intelligent item cleanup system for NeoForge 1.21.1*  
[![Smart item deleter V2](https://modfolio.creeperkatze.dev/modrinth/project/smart-item-deleter-v2?maxVersions=3)](https://modrinth.com/mod/smart-item-deleter-v2)[![Smart item deleter V2](https://modfolio.creeperkatze.dev/curseforge/project/1423151?maxVersions=3)](https://www.curseforge.com/minecraft/mc-mods/smart-item-deleter-v2)  

---

## 📘 Overview

**Smart Item Deleter v2** is a server-side optimization mod designed to automatically clean up dropped item entities when the item count exceeds a defined threshold.  
It tracks items individually to ensure fair, efficient, and safe removal — deleting only excess, old, and unimportant drops without disrupting normal gameplay.

> ✅ Supports NeoForge 21.1.215+, Youer 1.21.1, AsyncYouer-1.21.1  
> ⚙️ Designed for Create-based and heavily modded survival servers  
> 💾 Low overhead, deterministic cleanup cycles

---

## ⚙️ Configuration (for server owners)

Configuration file:
```
config/smart_item_deleter_v2-server.toml
```

| Option | Type | Default | Description |
|--------|------|----------|-------------|
| `entityCountThreshold` | `int` | `400` | Number of dropped items that pass the current filter/protection rules required before cleanup activates. |
| `minItemAgeMs` | `long` | `15000` | Minimum age (in milliseconds) before an item becomes eligible for deletion. Prevents immediate removal of new drops. |
| `scanIntervalTicks` | `int` | `20` | How often (in ticks) the system scans the world for items (20 ticks = 1 second). |
| `scanJitterEnabled` | `boolean` | `true` | Adds small random offset (±`scanJitterTicks`) to interval to reduce server tick spikes when multiple mods act simultaneously. |
| `scanJitterTicks` | `int` | `2` | Maximum jitter added/subtracted from each cleanup cycle’s timing. |
| `consoleDebugLogging` | `boolean` | `false` | When `true`, prints a single summary line in the console; cleanup details are always written to `logs/sidV2/cleanup.log`. |
| `deletePercentage` | `int` | `80` | Percentage of eligible items to delete each cycle (0–100). Protects the newest items even when threshold is exceeded. |
| `protectNamedItems` | `boolean` | `true` | When enabled, items with custom names are ignored and never deleted. |
| `filterMode` | `enum` | `BLACKLIST` | `BLACKLIST` protects listed items; `WHITELIST` targets only listed items. |
| `filterList` | `list` | `["minecraft:nether_star", "#modid:valuable"]` | Accepts exact item IDs (`minecraft:stone`), tag references (`#forge:ingots`), or wildcard globs with `*`/`?` (e.g., `minecraft:*`, `minecraft:oak*`). |

### Example:
```toml
entityCountThreshold = 250
minItemAgeMs = 15000
scanIntervalTicks = 20
deletePercentage = 80
protectNamedItems = true
filterMode = "BLACKLIST"
filterList = ["minecraft:nether_star", "minecraft:diamond"]
```

#### Wildcard example
```toml
filterList = ["minecraft:oak*"]
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
- Cleanup only occurs **when the count of items that pass the current filter/protection rules exceeds the configured threshold**.
- Items are eligible if:
    1. Their age ≥ `minItemAgeMs`
    2. They pass the current policy filter (`filterMode` + `filterList`)
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
| Command | Description                                                                    |
|----------|--------------------------------------------------------------------------------|
| `/cleanup now` | Forces a cleanup cycle manually.                                               |
| `/cleanup now force` | Forces a cleanup, ignoring threshold and age checks.                    |
| `/cleanup stats` | Displays tracked item count, eligible items, and current thresholds.        |
| `/cleanup config list` | Lists configurable values and current settings.                       |
| `/cleanup config <key>` | Reads a single config value.                                         |
| `/cleanup config <key> <value>` | Updates a config value at runtime.                        |

### Logging
- Cleanup details go to `logs/sidV2/cleanup.log` (console summary only when `consoleDebugLogging=true`).
- `/cleanup stats` output is saved to `logs/sidV2/stats.log` every time.
- On server start, existing `cleanup.log` and `stats.log` are zipped in `logs/sidV2/`.

---

## 💡 Future Plans
- Provide in-game feedback about the cleanup stats
- Expose metrics to `/cleanup stats` to some kind of endpoint like a json file. 

---

## 📜 License
MIT License — freely usable and modifiable.  
Please credit `Metl_Play` if redistributed.  
Would be appreciated if I am mentioned in modpacks, but it's not required.

---

## 🧩 Credits
- **Developer:** Metl_Play
- **Minecraft:** 1.21.1 (NeoForge)
- **Mappings:** Parchment mappings
- **Libraries:** NeoForge API, standard Java collections

> “It’s not just a cleaner mod — it’s a smarter janitor.” 🧠🧹
