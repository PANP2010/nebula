# Changelog

All notable changes to Nebula will be documented in this file.

## [Unreleased]

### Added
- TBD

## [0.2.0] - 2026-07-14

### First Playable Release

This release marks the first **playable version** of Nebula — a deterministic DAG shadow runtime for Paper and Folia Minecraft servers. The core DAG execution pipeline has been verified on a real Folia 26.1.2 server.

### Verified on Real Server (2026-07-08 through 2026-07-12)
- End-to-end DAG execution on real Folia 26.1.2 server (live lever→wire→lamp circuit)
- Deterministic zero-diff capture: byte-for-byte identical replay files verified
- Per-tick MSPT verified at multi-region scale: p99 1.914ms < 3ms budget
- Entity MOVE live path + RW-guard verification
- Block-entity (hopper/furnace/dropper) live slices + guard
- Parallel DAG execution verified against single-thread oracle (Paper differential)
- Settled-state divergence gate now passes deterministically
- 742 unit tests passing

### Added
- `ToggleSourceClassifier` + `ToggleSourceRegistry` for game layer toggle tracking
- `/nebula scan` — region-thread-safe rescan of loaded chunks
- `RedstoneCasStateHasher` — thread-safe state hashing from CAS store
- `/nebula capture stop` saves timestamped `.nrp` replay files
- `TickTimeRecorder` + `/nebula perf [reset]` for DAG tick timing metrics
- `DeterministicToggleSchedule` + `LiveLoadToggleDriver` for driven live-load testing
- `/nebula diff` for Paper differential comparison
- `/nebula diag` for per-invocation cascade diagnostics
- `/nebula random` for random budget control
- `/nebula dag-stats` + `FastBuildStats` for DAG build performance
- `/nebula fidelity` for fidelity degradation control
- `BudgetedDagBuilder` with T2 relaxed determinism
- Entity MOVE live path + RW-guard tracing
- Block-entity brewing/furnace/hopper/dropper live slices
- Fluid `BlockFromToEvent` observe-only path
- Explosion `EXPLOSION_BLOCK_DESTROY` observe-only path
- `AnnotationCoverageDashboard` for RW-set coverage metrics
- `BridgeAnnotationScanner` for bridge method coverage tracking
- Nebula Regression CI workflow with JaCoCo coverage
- Aliyun Maven mirror configuration

### Fixed
- **World-name key mismatch (B3)**: Normalized `RedstoneTickHook.key()` through `DimensionIds.fromName()`
- **Zero-diff capture (B6)**: Fixed hasher's tracked-position set and thread-safe hashing
- **RedstoneTickHook lifecycle driver**: Added `GlobalRegionScheduler.runAtFixedRate` for tick lifecycle
- **componentMap empty**: Added synchronous scan in `onEnable()` and `/nebula scan` command
- **settled-state divergence gate**: Fixed `beginTick` race condition

### Known Limitations
- **DG2 (Entity)**: collision/AI/item/damage, 50k zero-diff, formal random-budget acceptance remain open
- **DG3 (Full System)**: VAP plugin compatibility and 100-player load testing not verified
- **B4 (Performance)**: Baseline-vs-Nebula MSPT comparison not applicable (observe-only architecture)
- Write-back intentionally off until zero-diff validation gates it

## [0.1.0] - 2026-06-18

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