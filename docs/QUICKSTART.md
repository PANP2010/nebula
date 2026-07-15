# Nebula Quick Start Guide

Fastest path to building, deploying, and smoke-testing Nebula. For the full
status picture read [PROJECT_STATUS.md](PROJECT_STATUS.md) first — it is the
source of truth for what is verified versus source-wired-but-unverified.

Nebula has **two distinct runtime paths**. Pick the one that matches your goal:

| Path | What it is | Maturity |
|------|-----------|----------|
| **Plugin / agent shadow runtime** | A Bukkit plugin (+ optional Java agent) that runs a non-authoritative DAG alongside a stock Folia/Paper server | Live-verified for the redstone slice; the recommended path for reproducible experiments |
| **Native patched server** | A Folia-derived fork with a `NEBULA` tick driver that executes an authoritative tick DAG | Source-wired but currently **does not clean-build** and has **no recorded live boot** — see PROJECT_STATUS.md |

Unless you specifically want to work on the native fork, use the plugin path.

---

## Prerequisites

- **Java 21** — builds the Gradle project (`JAVA_HOME` for Gradle)
- **Java 25** — runtime for `nebula-folia-adapter` (NMS) and for running a Folia 26.1.2 server
- **Folia 26.1.2** — the API jar belongs at `libs/folia-api-26.1.2.build.8-stable.jar` (see `libs/README.md`)
- A Folia 26.1.2 server jar of your own, for deployment (not committed to this repo)

Toolchain paths (`org.gradle.java.installations.paths`) are set in
`gradle.properties`; adjust them to your machine if your JDKs live elsewhere.

---

## Plugin / agent shadow path

### 1. Build the plugin

```bash
./gradlew :nebula-plugin:shadowJar
```

The shadow jar is written under the module's build directory as
`nebula-plugin-<version>.jar` (the current project version is `0.2.0`, set in
`build.gradle.kts`). Use `./gradlew properties | grep version` if you need to
confirm the version programmatically rather than hard-coding a filename.

### 2. Deploy

Copy the built `nebula-plugin-<version>.jar` into your Folia server's
`plugins/` directory, then start the server. Confirm it loaded with `/plugins`
— `Nebula` should appear.

The optional Java agent (`nebula-agent`) enables bytecode-level redstone
interception. It is added via `-javaagent:<path-to-agent-jar>` in your server's
JVM arguments. Without it, Nebula falls back to the Bukkit `BlockRedstoneEvent`
listener, which is sufficient for the smoke test below.

### 3. Smoke test

RCON `setblock`/`fill` do not fire `BlockPlaceEvent`, so command-placed redstone
must be registered with `/nebula scan` before the DAG sees it.

```
/nebula status              # component count, CAS store sizes, tracked positions
/setblock 0 72 0 minecraft:lever
/fill 1 72 0 15 72 0 minecraft:redstone_wire
/setblock 16 72 0 minecraft:redstone_lamp
/nebula scan                # register the command-placed components
/setblock 0 72 0 minecraft:lever[powered=true]   # toggle → drives the DAG
/nebula perf                # DAG tick timing + microstep percentiles
```

A successful toggle logs a line like `DAG tick: N tasks, M microsteps in Xms`.

### Useful commands

The full surface is defined in `NebulaCommand.java`. Commonly used:

| Command | Purpose |
|---------|---------|
| `/nebula status` | Component count, CAS store sizes, tracked positions |
| `/nebula scan` | Rescan loaded chunks for redstone components |
| `/nebula perf [reset]` | DAG tick timing + microstep percentiles |
| `/nebula capture start <ticks> [--drive <seed>]` | Record a `.nrp` capture (optionally driven) |
| `/nebula capture stop` | Stop capture, save timestamped `.nrp` |
| `/nebula diag [on\|off]` | Per-invocation cascade diagnostics |
| `/nebula diff` | Paper differential comparison (non-Folia only) |
| `/nebula dag-stats` | DAG build performance |
| `/nebula random`, `/nebula fidelity`, `/nebula coverage`, `/nebula survival` | Budget / fidelity / RW-coverage / player-subsystem controls |

### Optional JVM flags

| Flag | Effect |
|------|--------|
| `-Dnebula.rw.guard=true` | Enable the runtime RW-set integrity guard (plugin path) |
| `-Dnebula.rw.guard.sample=<0..1>` | Sample fraction for guard tracing |
| `-Dnebula.dag.parallel=true` | Run the plugin DAG on a worker pool (used for the Paper differential) |

---

## Native patched-server path

> **Status: not a verified release procedure.** The generated native source
> currently has a compile blocker in `ServerLevel`, the embedded module sources
> are non-portable symlinks, and no live `NEBULA`-mode boot has been recorded.
> See PROJECT_STATUS.md → "Build & release health" before spending time here.

The native fork lives under `nebula-server-build/`. Its default scheduler is set
to `NEBULA` in source (`0008-Set-default-scheduler-to-NEBULA-tick-driver.patch`),
but the checked-in runtime `config/paper-global.yml` selects `EDF`, so a launch
from the repo root uses stock Folia EDF, not the Nebula driver. To exercise the
native driver you must both build the fork and set `threaded-regions.scheduler:
NEBULA`.

Intra-layer parallelism in the native path is off unless
`-Dnebula.parallel=true` is set (see `NebulaParallelIntegration`). Native RW-guard
activation uses `-Dnebula.guard=true`.

---

## Running tests

```bash
# All modules (long-running "slow" replays are excluded by default)
./gradlew test

# Include the DG1-scale replays
./gradlew test -Pslow

# A single module
./gradlew :nebula-plugin:test
```

---

## Key documents

- [PROJECT_STATUS.md](PROJECT_STATUS.md) — source of truth for current status
- [nebula-architecture.md](nebula-architecture.md) — system architecture (design)
- [dag-execution.md](dag-execution.md) — how the DAG executor works
- [runtime-rw-guard.md](runtime-rw-guard.md) — RW-set integrity guard

Older bring-up and acceptance documents (INTEGRATION_TESTING_GUIDE.md, the DG
reports, DEVELOPMENT_PLAN.md, NEBULA_BLOCKERS.md) are dated records — see the
index in [README.md](README.md) for how they are classified.
