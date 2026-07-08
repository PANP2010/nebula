# Changelog

All notable changes to Nebula will be documented in this file.

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

**Integration Blockers** (see docs/PROJECT_STATUS.md):
- **B1**: RedstoneTickHook lifecycle driver implementation in progress
- **B2**: WorldRedstoneScanner async timing race (fix committed, needs verification)
- **B3**: Agent bytecode injection chain not yet verified on real server
- **B4**: NMS bridge performance not yet measured under real load

**Feature Limitations**:
- Zero-diff validation requires running against real Folia server with test world
- Entity physics DAG runner does not yet integrate with MicroStepScheduler microstep expansion
- Command permissions are op-only by default
- Capture harness never run end-to-end

**Test Coverage**:
- ✅ 659 unit tests pass (100% success rate)
- ⏳ Integration tests in progress
- ❌ End-to-end DAG execution not yet verified on real server