# Nebula Server

**Deterministic multi-core Minecraft server architecture — Development Preview v0.1.0-SNAPSHOT**

Nebula is a proof-of-concept for deterministic, region-aware tick execution on Folia. It aims to demonstrate that causally-independent tasks (redstone, entity physics, tile entities) can share a unified DAG and execute in parallel across regions while maintaining deterministic semantics.

✅ **Milestone (2026-07-08)**: End-to-end DAG execution is now **verified on a real Folia 26.1.2 server**. A live lever→wire→lamp circuit toggled via RCON produced repeatable, exception-free DAG ticks (`DAG tick: 3 tasks, 1 microsteps in 12ms`). Core components are built and unit-tested (662 tests passing). Correctness (zero-diff) and performance verification are the next milestones. See [PROJECT_STATUS.md](docs/PROJECT_STATUS.md) for detailed status.

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
| `/nebula capture stop` | Stop capture, report frame count | `nebula.capture` |
| `/nebula status` | Show CAS store sizes and tracked positions | `nebula.status` |
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
| NMS bridges (Block, Entity, BlockEntity) | ✅ Pass | ⏳ Testing | Interfaces complete, sync path unverified |
| MicroStepScheduler (redstone DAG) | ✅ Pass | ⏳ Testing | Logic complete, needs real redstone |
| EntityTickExecutor (entity DAG) | ✅ Pass | ❌ Not wired | Created but not integrated |
| CompositeTaskRunner (unified DAG) | ✅ Pass | ⏳ Testing | Wired but not verified |
| Zero-diff capture framework | ✅ Pass | ❌ Not run | Framework exists, never captured |
| In-game commands | ✅ Pass | ⚠️ Partial | `/status` works, `/capture` untested |
| Shadow jar deployable | ✅ Pass | ✅ Works | Deploys successfully |
| **End-to-end DAG execution** | N/A | ✅ **Verified** | **Fires on real Folia** — 45 DAG ticks from live circuit toggles, 0 exceptions (2026-07-08) |

**Summary**: All components pass unit tests (662) and end-to-end DAG execution is verified on real Folia. Correctness (zero-diff capture) and performance (MSPT) verification are next. See [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md) for detailed status.

## License

MIT (see LICENSE file)

## Authors

NebulaArchTeam