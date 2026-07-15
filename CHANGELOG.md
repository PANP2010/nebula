# Changelog

All notable changes to Nebula will be documented in this file.

## [Unreleased]

Post-0.2.0 work on the branch. **This section tracks source that is present in the
tree but not all live-verified or clean-building** — see
[docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md) for the per-path status.

### Added — native patched server (`nebula-server-build`)
- `NEBULA` tick-driver path: `TickRegions` selects a `NebulaTickDriver` (coordinator +
  `availableProcessors()-1` region workers) instead of Folia's EDF scheduler pool
- `ServerLevel.nebula$tickViaDAG`: authoritative per-tick DAG built from real NMS work —
  coarse world tasks (`RegionTickDecomposer`), per-entity tasks (`EntityTaskBuilder`),
  and per-block-entity tasks (`BlockEntityTaskBuilder`), executed via `TickPipeline`
- Per-tick entity RCU snapshot (`NebulaSnapshotRegistry`) consumed by the
  `NearestLivingEntitySensor` / `NearestItemSensor` AI sensors
- `NebulaParallelIntegration` intra-layer parallel runner (opt-in, `-Dnebula.parallel=true`)
- `NebulaExplosionPipeline` (telemetry-only sub-DAGs; vanilla explosion stays authoritative),
  `NebulaGuardIntegration`, `NebulaTickMetrics`, `NebulaReplayIntegration` scaffolding
- Default source config sets `threaded-regions.scheduler: NEBULA` (patch 0008)

### Added — maintenance + player modules
- `nebula-maintenance`: `@NebulaRW` annotation source inferrer, patch generator, and verifier
- `nebula-player`: player DAG scaffolding (`PlayerTaskFactory`, `PlayerTaskRunner`,
  `PlayerAuthorityGate`, `PlayerDivergenceSampler`) — shadow/observe only

### Added — new classes (post-0.2.0 worktree)

#### Fidelity and build-time budget
- `FidelityTierAdapter` (nebula-core): Wires the active `FidelityTier` to SCC contraction thresholds (T0/T1/FALLBACK: 128, T2: 1024, T3: unlimited) and notifies AI staleness sink subscribers when previous-tick snapshots are permitted
- `DagBuildBudget` (nebula-core): Tracks per-tick DAG build times, activates avalanche guard after 10 consecutive over-budget ticks; threshold is 2ms (4% of 50ms tick)
- `BudgetedDagBuilder` (nebula-core): Enhanced with avalanche guard — falls back to `CoarseDagBuilder.serialChain()` when budget warning is active, otherwise uses `BucketDagBuilder` with timing recording

#### VAP plugin certification
- `PluginCertificationHarness` (nebula-core): Runs synthetic probes through the real `PluginTaskQueue` to certify third-party plugins; determines level (NEBULA_READY ≥100%, NEBULA_OPTIMIZED ≥80%, NEBULA_NATIVE ≥50%, uncertified <50%)
- `PluginCertificationHarnessTest` (nebula-core/test)

#### Maintenance tooling
- `HotspotInventoryBridge` (nebula-maintenance): Wires real async-profiler hotspot reports to the `AnnotationCoverageDashboard`; provides `/nebula coverage` with both "public bridge methods" and "real hot methods" denominators
- `HotspotInventoryBridgeTest` (nebula-maintenance/test)
- `SpatialBucketScaleTest` (nebula-core/test): Scale tests for spatial 32-chunk-bucket partitioning at 5k entity workloads

#### Folia bridge tick hooks (enhanced)
- `BlockEntityTickHook`: Added `beginTick()`/`endTick()` with full `@NebulaRW` annotations; tracks dirty hoppers/furnaces/droppers
- `PlayerTickHook`: Added `beginTick()`/`recordPlayerSnapshot()`/`endTick()` with `@NebulaRW` annotations; tracks player move/interact events
- `RedstoneTickHook`: Added `beginTick()` with `@NebulaRW` annotation; stamps tick boundary and cleans stale guards

### Known gaps (not yet verified)

Based on code verification (2026-07-15):

- **Native fork compilation**: Code appears complete with no obvious compile errors in `ServerLevel.java`; however, actual compilation has not been verified with `./gradlew`
- **Native NEBULA boot**: No recorded evidence of a successful native-mode boot; all available logs show `EDF` scheduler
- **Native fork determinism**: Parallel-safe entity declaration not yet justified; native RW-guard not wired (`nebula.guard` vs `nebula.rw.guard`)
- **VAP PluginTaskQueue integration**: Classes exist (`PluginTaskQueue`, `PluginCertificationCatalog`, etc.); actual usage in `NebulaPlugin` not yet verified
- **Config drift**: `config/paper-global.yml` still selects `EDF`; native driver not enabled by default
- **Player write-back**: Shadow/observe mode only; `writeToNms` path not wired
- **DG2/DG3 acceptance tests**: Framework complete; formal acceptance testing not performed

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
- 742 unit tests passing (release-time count; not a current globally-green claim — some
  post-release module suites, e.g. `nebula-player`, are currently failing)

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