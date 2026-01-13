# Changelog

## 0.3.1-neoforged - 2025-11-29
Changes since 0.2.3-asyncyouer (tag: Release).

### Added
- Added `/cleanup config` commands to list, read, and set config values at runtime with tab completion, validation against the config spec, and saving changes before re-baking settings.
- Added automatic discovery of config bindings and tracking of the active server config during load and reload so runtime updates target the correct spec.

### Changed
- Bumped NeoForge to 21.1.215 and retargeted the mod version to `0.3.1-neoforged`.
- Disabled console cleanup summaries by default (configurable via `consoleDebugLogging`).
- Documentation now calls out support for NeoForge 21.1.215 plus Youer/AsyncYouer environments and refreshed roadmap notes.
- Replaced chunk AABB scanning with `level.getAllEntities()` and per-level schedules to keep cleanup in lockstep with async ticking environments.

## 0.2.3 - 2025-11-09
- Added wildcard-aware matching for `filteredItems`, translating `*` and `?` patterns into regex so namespace-wide and prefix filters (e.g., `minecraft:*`, `minecraft:oak*`) work. Filtered items still count toward thresholds.

## 0.2.2 - 2025-11-07
- New `consoleDebugLogging` toggle to silence cleanup summaries when desired (recommended to keep on while validating setup).
- Resolved the issue tracked in GitHub issue #5.

## 0.2.1 - 2025-11-03
- Implemented `/cleanup now`, `/cleanup now force`, `/cleanup stats`, and `/cleanup dryrun` with aggregated feedback, per-dimension reporting, and dry-run previews.
- Refined the cleanup engine to expose analysis summaries, support forced execution overrides, and improve context-rich logging.
- `/cleanup` now uses the standard permission system for OPs/console and removes unused code paths while improving persistence dirty-marking accuracy.

## 0.1.2 - 2025-11-02
- Respected jitter configuration, fixed NPE when the filter list was empty, stopped persistent data from growing infinitely, and corrected `setDirty` from firing every tick.

## 0.1.1 - 2025-10-07
- Initial beta release for testing; compatible with Youer (not AsyncYouer) and smoke-tested alongside 50+ mods.
