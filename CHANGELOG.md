# Changelog

All notable changes to Nebula will be documented in this file.

## [Unreleased] - 2026-07-08

### Verified: DAG execution and zero-diff capture on real Folia

The two core properties Nebula was built to demonstrate are now empirically
verified on a real Folia 26.1.2 server (previously only unit-tested in isolation).

**Added**
- `/nebula scan` — region-thread-safe rescan of loaded chunks, so redstone placed
  via commands (RCON `setblock`, which does not fire `BlockPlaceEvent`) can be
  registered for DAG execution. Registered-component count added to `/nebula status`.
- `RedstoneCasStateHasher` — reads redstone state from the thread-safe
  `RedstoneWorldState` CAS store instead of live NMS blocks, making zero-diff
  hashing safe from the global tick thread and reflective of Nebula's computed state.
- `/nebula capture stop` now saves a timestamped `.nrp` replay file under
  `plugins/Nebula/captures/` and reports distinct-hash count + first/last hash.
- `TickTimeRecorder` (nebula-core/metrics) + `/nebula perf [reset]` (B4) — a
  thread-safe percentile recorder for DAG tick execution time (p50/p95/p99 over a
  recent-sample window plus lifetime count/min/max/avg). Replaces the per-tick INFO
  log line (demoted to FINE) that both flooded the log and skewed the cost it
  measured. Warns when p99 alone exceeds the 50ms/20-TPS budget.

**Fixed**
- **World-name key mismatch (B3)**: `RedstoneTickHook` recorded dirty positions
  under the NMS namespaced key (`minecraft:overworld`) but drained with the Bukkit
  folder name (`world`), so every agent-recorded update was silently dropped.
  Normalized `RedstoneTickHook.key()` through `DimensionIds.fromName()`.
- **Zero-diff capture (B6)**: the hasher's tracked-position set was never populated,
  and `WorldStateHasher` read NMS blocks off the region thread (NPE on Folia).

**Verified**
- End-to-end DAG execution: live circuit toggles produced 45 exception-free DAG
  ticks (`DAG tick: 16 tasks, 14 microsteps`).
- Deterministic zero-diff: two identical 40-tick captures produced byte-for-byte
  identical replay files.
- Per-tick MSPT (B4): steady-state small circuit measured avg 1.24ms / p50 0.95ms /
  p95 2.86ms / p99 3.47ms over 100 ticks via `/nebula perf`, well under the
  50ms/20-TPS budget (a ~57ms first-tick JIT-warmup outlier ages out of the window).
- 715 unit tests pass (was 659; +9 for the DeterministicToggleSchedule schedule slice, +9 for the LiveLoadToggleDriver resolution slice, and +6 for the FoliaToggleApplier live seam, 2026-07-09).

**Still unverified**: performance *under load* (B4) — per-tick cost is now measured on
a small circuit, but large multi-region load testing and a baseline-vs-Nebula MSPT
comparison do not exist yet.

## [0.1.0-SNAPSHOT] - 2026-06-18

### Development Preview Release

This release contains all core components for deterministic multi-core tick execution on Folia 26.1.2, 
with 659 passing unit tests. End-to-end integration testing is in progress.

**Status**: Components built and tested in isolation. DAG execution on real Folia server under active development (see docs/PROJECT_STATUS.md for detailed status).

### Features

- **Three-phase tick execution**: sync-from-NMS → DAG → sync-to-NMS
- **Redstone DAG**: MicroStepScheduler with microstep expansion, change-aware downstream generation
- **Entity physics DAG**: EntityTickExecutor with MOVE/COLLISION actions
- **CompositeTaskRunner**: unified cross-subsystem DAG execution (redstone + entity)
- **NMS bridges**: CAS ↔ real Bukkit state for blocks, entities, tile entities
  - `NmsBlockStateBridge`: redstone wire power levels
  - `NmsEntityStateBridge`: entity position/velocity
  - `NmsBlockEntityStateBridge`: hopper/furnace timer + inventory
- **Zero-diff capture framework**: `FoliaCaptureHarness` + `WorldStateHasher` (SHA-256)
- **In-game commands**: `/nebula capture start/stop`, `/nebula status`, `/nebula help`
- **Shadow jar**: 513KB deployable plugin for Folia 26.1.2

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     NebulaPlugin                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐│
│  │ CAS Stores   │  │ NMS Bridges  │  │ DAG Executors        ││
│  │ Redstone     │  │ Block        │  │ MicroStepScheduler   ││
│  │ Entity       │  │ Entity       │  │ EntityTickExecutor   ││
│  │ BlockEntity  │  │ BlockEntity  │  │ CompositeTaskRunner  ││
│  └──────────────┘  └──────────────┘  └──────────────────────┘│
│                         ↓                                     │
│  ┌──────────────────────────────────────────────────────────┐│
│  │              FoliaRegionTickExecutor                      ││
│  │  Phase 1: syncFromNms → Phase 2: DAG → Phase 3: syncToNms││
│  └──────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
```

### Pull Requests

- #9: Plugin migration to Java 25 + Folia 26.1.2
- #10: NmsBlockStateBridge (redstone CAS↔NMS)
- #11: NmsEntityStateBridge (entity CAS↔NMS)
- #12: NmsBlockEntityStateBridge (tile entity CAS↔NMS)
- #13: FoliaCaptureHarness (zero-diff capture framework)
- #14: Full pipeline integration into NebulaPlugin
- #15: MicroStepScheduler wired into live DAG tick
- #16: E2E integration test
- #17: EntityTickExecutor (entity physics DAG)
- #18: CompositeTaskRunner (unified cross-subsystem DAG)
- #19: /nebula commands and permissions
- #20: README documentation update

### Testing

- 56 test tasks pass
- E2E integration test validates all components wired correctly
- Unit tests for NMS bridges, state hasher, capture harness

### Known Limitations & Active Work

> **Note (2026-07-08)**: B1, B2, B3 and B6 below have since been RESOLVED and
> verified on a real server — see the `[Unreleased]` entry at the top. This
> section reflects the state as of the 2026-06-18 preview and is kept for the record.

**Integration Blockers** (see docs/PROJECT_STATUS.md):
- **B1**: RedstoneTickHook lifecycle driver implementation in progress → ✅ resolved
- **B2**: WorldRedstoneScanner async timing race (fix committed, needs verification) → ✅ resolved
- **B3**: Agent bytecode injection chain not yet verified on real server → ✅ fixed (key mismatch)
- **B4**: NMS bridge performance not yet measured under real load → ⏳ still open

**Feature Limitations**:
- Zero-diff validation requires running against real Folia server with test world
- Entity physics DAG runner does not yet integrate with MicroStepScheduler microstep expansion
- Command permissions are op-only by default
- Capture harness never run end-to-end → ✅ resolved (verified 2026-07-08)

**Test Coverage**:
- ✅ 659 unit tests pass (100% success rate) — now 691 as of 2026-07-08
- ✅ End-to-end DAG execution verified on real server (2026-07-08)