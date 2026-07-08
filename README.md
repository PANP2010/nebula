# Nebula Server

**Deterministic multi-core Minecraft server architecture — Development Preview v0.1.0-SNAPSHOT**

Nebula is a proof-of-concept for deterministic, region-aware tick execution on Folia. It aims to demonstrate that causally-independent tasks (redstone, entity physics, tile entities) can share a unified DAG and execute in parallel across regions while maintaining deterministic semantics.

✅ **Milestones (2026-07-08)**: Two core properties are now **verified on a real Folia 26.1.2 server**:
1. **End-to-end DAG execution** — a live lever→wire→lamp circuit toggled via RCON produced repeatable, exception-free DAG ticks (`DAG tick: 3 tasks, 1 microsteps in 12ms`).
2. **Deterministic zero-diff capture** — two identical 40-tick captures produced byte-for-byte identical replay files.

Core components are built and unit-tested (742 tests passing). **Performance under load is the remaining unverified milestone** — per-tick MSPT is now measured (`/nebula perf`, Folia-verified on a small circuit) but load testing and a baseline-vs-Nebula comparison do not exist yet. This is a working prototype, not a performance-validated or "playable" release. See [PROJECT_STATUS.md](docs/PROJECT_STATUS.md) for detailed status.

## Features (v0.1.0)

- **Three-phase tick execution**: sync-from-NMS → DAG → sync-to-NMS
- **Redstone DAG**: MicroStepScheduler with microstep expansion, change-aware downstream generation
- **Entity physics DAG**: EntityTickExecutor with MOVE/COLLISION actions
- **CompositeTaskRunner**: unified cross-subsystem DAG execution
- **NMS bridges**: CAS ↔ real Bukkit state for blocks, entities, tile entities
- **Zero-diff capture framework**: FoliaCaptureHarness + SHA-256 state hasher
- **In-game commands**: `/nebula capture start/stop`, `/nebula status`

## Requirements

- **Java 21** for build (Gradle 8.13)
- **Java 25** for nebula-folia-adapter (JDK at `/home/kuli/jdks/jdk-25.0.3` or configure in `build.gradle.kts`)
- **Folia 26.1.2** server (API jar in `libs/folia-api-26.1.2.build.8-stable.jar`)

## Building

```bash
# Full build (all modules)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew build --offline

# Shadow jar (deployable plugin)
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :nebula-plugin:shadowJar
```

Shadow jar output: `~/.gradle/nebula-server-build/nebula-server/nebula-plugin/libs/nebula-plugin-0.1.0-SNAPSHOT.jar`

## Deployment

1. Copy `nebula-plugin-0.1.0-SNAPSHOT.jar` to your Folia 26.1.2 server's `plugins/` directory
2. Start the server
3. Verify plugin loaded: `/plugins` should show `Nebula`

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/nebula capture start [ticks]` | Start state capture for N ticks (default 1000) | `nebula.capture` |
| `/nebula capture stop` | Stop capture, save a timestamped `.nrp`, report frame + distinct-hash count | `nebula.capture` |
| `/nebula scan` | Rescan loaded chunks for redstone components (needed for RCON/command-placed redstone) | `nebula.status` |
| `/nebula status` | Show component count, CAS store sizes, and tracked positions | `nebula.use` |
| `/nebula perf [reset]` | Show DAG tick timing + microstep percentiles, auto-graded against DG1 Criteria 2/3 | `nebula.status` |
| `/nebula help` | Show command help | `nebula.use` |

## Architecture Overview

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

## Project Structure

- `nebula-core`: RW-set, DAG primitives, task scheduling
- `nebula-guard-api`: runtime RW-set integrity guard
- `nebula-redstone`: redstone world state, actions, MicroStepScheduler
- `nebula-entity`: entity physics state, EntityTickExecutor
- `nebula-folia-bridge`: Folia API abstractions, capture harness
- `nebula-folia-adapter`: NMS bridges (requires Java 25)
- `nebula-plugin`: Bukkit plugin entry point, commands

## Testing

```bash
# All tests
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew test

# Specific module
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :nebula-plugin:test
```

## Current Status

| Component | Unit Tests | Integration | Status |
|-----------|------------|-------------|--------|
| Plugin toolchain (Java 25 + Folia 26.1.2) | ✅ Pass | ✅ Loads | Complete |
| NMS bridges (Block, Entity, BlockEntity) | ✅ Pass | ✅ Redstone verified | Block sync drives live redstone; perf unmeasured |
| MicroStepScheduler (redstone DAG) | ✅ Pass | ✅ Verified | Runs on real redstone (up to 16 tasks / 14 microsteps observed) |
| EntityTickExecutor (entity DAG) | ✅ Pass | ❌ Not wired | Created but not integrated into live tick path |
| CompositeTaskRunner (unified DAG) | ✅ Pass | ⏳ Redstone only | Redstone route verified; entity route not exercised live |
| Zero-diff capture framework | ✅ Pass | ✅ Verified | Two identical captures → byte-for-byte identical replay files |
| In-game commands | ✅ Pass | ✅ Verified | `/status`, `/scan`, `/capture` all exercised on live server |
| Shadow jar deployable | ✅ Pass | ✅ Works | Deploys successfully |
| **End-to-end DAG execution** | N/A | ✅ **Verified** | **Fires on real Folia** — 45 DAG ticks from live circuit toggles, 0 exceptions (2026-07-08) |

**Summary**: All components pass unit tests (691). End-to-end DAG execution and deterministic zero-diff capture are both verified on real Folia. Per-tick MSPT is now measured (`/nebula perf`); performance verification *under load* is the remaining milestone. See [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md) for detailed status.

## License

MIT (see LICENSE file)

## Authors

NebulaArchTeam