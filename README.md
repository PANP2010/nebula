# Nebula Server

![Nebula Logo](docs/images/nebula-logo-flat.png)

**Deterministic multi-core Minecraft server architecture — v0.2.0**

Nebula explores deterministic, region-aware tick execution for Paper and Folia. The
goal is to demonstrate that causally-independent tasks (redstone, entity physics,
block entities) can share a unified DAG and execute in parallel while preserving the
state a single-threaded server would produce.

The repository contains **two distinct runtime paths**. They have very different
maturity, and conflating them has caused documentation drift in the past — so they are
described separately throughout the docs.

| Path | What it is | Maturity |
|------|------------|----------|
| **Plugin / agent shadow runtime** | A Bukkit plugin (`nebula-plugin`) plus an optional Java agent that runs the DAG as a non-authoritative shadow alongside Folia's own tick. | Live-verified on real Folia 26.1.2 for redstone and targeted entity/block-entity slices. This is the path behind the `v0.2.0` release evidence. |
| **Native patched server** | A Folia-derived fork (`nebula-server-build`) with a `NEBULA` tick driver that executes the DAG as the authoritative tick. | Source-wired but **not currently verified**: the generated server source has a build blocker, embedded-module sources are non-portable symlinks, and there is no recorded live `NEBULA` boot. See [PROJECT_STATUS.md](docs/PROJECT_STATUS.md). |

For an honest, source-backed assessment of what works and what does not, read
[docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md) — it is the source of truth for status.

## Status at a glance

Labels are kept deliberately separate:

- **Live-verified** — exercised on a real server with recorded evidence.
- **Source-wired** — code path is connected in production source but not confirmed live.
- **Gated** — implemented but off unless a flag/config is set.
- **Telemetry-only** — runs, but records/observes without replacing vanilla behavior.
- **Incomplete** — partial, dormant, or unwired.

| Subsystem | Plugin / agent shadow | Native patched server |
|-----------|----------------------|-----------------------|
| Redstone | Live-verified (shadow, observe-only) | Source-wired, but real redstone still runs inside the coarse vanilla block-tick task |
| Entity physics | MOVE live-verified; collision/AI/item/damage incomplete | Source-wired per-entity tasks (authoritative when `NEBULA` active) |
| Block entities | hopper/furnace/dropper live-verified (shadow); write-back off | Source-wired per-block-entity tasks; precise RW-sets for a subset |
| Fluids | Observe-only slice | Source-wired as one coarse task |
| Explosions | Observe-only slice | Telemetry-only sub-DAG (vanilla still authoritative) |
| Lighting | Incomplete (library code, unwired) | Incomplete (upstream light engine) |
| AI / pathfinding | Incomplete (library code, unwired) | Runs inside entity tick; two sensors read the RCU snapshot |
| Players | Incomplete (wiring defects) | Incomplete |
| VAP plugin layer | Incomplete (classes present, not constructed) | Registration only; pipeline queue is null |
| Parallel DAG execution | Verified vs single-thread oracle on Paper (`-Dnebula.dag.parallel`) | Gated behind `-Dnebula.parallel=true` |

## Verified milestones (plugin/agent shadow path)

Recorded on real Folia 26.1.2 / Paper 26.1.2 between 2026-07-08 and 2026-07-12:

- **End-to-end DAG execution** — a live lever→wire→lamp circuit produced repeatable,
  exception-free DAG ticks.
- **Deterministic zero-diff capture** — two identical captures produced byte-for-byte
  identical `.nrp` replay files.
- **Parallel == single-thread oracle** — on Paper, the 12-worker DAG matched the
  single-thread authority 48/48 across two circuits (`/nebula diff`).
- **Shadow-overhead budget** — p99 DAG tick 1.914 ms < 3 ms at multi-region scale.
- **Targeted live slices** — entity MOVE + RW-guard, hopper/furnace/dropper block
  entities, and small fluid/explosion guard paths.

These validate the shadow-runtime concept and the core parallel-determinism claim for
redstone. They do **not** establish authoritative full-server execution — see the
status report for the boundary.

## Requirements

- **Java 21** for the Gradle build.
- **Java 25** toolchain/runtime for `nebula-folia-adapter` and NMS-facing modules.
- **Folia 26.1.2** API jar in `libs/` (see [libs/README.md](libs/README.md); the jar
  is a local, git-ignored artifact).

## Building the plugin

```bash
./gradlew :nebula-plugin:shadowJar
```

The shadow jar version follows the root project version (`0.2.0`); the artifact is
named `nebula-plugin-0.2.0.jar`. The build output directory is redirected under
`~/.gradle/nebula-server-build/` (see `build.gradle.kts`).

> The native `nebula-server-build` fork does not currently produce a clean server jar
> from this checkout. Do not treat native-mode instructions as a verified release
> procedure. See [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md) for the specific
> blockers.

## Deploying the plugin (shadow path)

1. Copy `nebula-plugin-0.2.0.jar` into your Folia 26.1.2 server's `plugins/` directory.
2. Start the server.
3. Confirm it loaded: `/plugins` should list `Nebula`.
4. Confirm the version from `/plugins`, `plugin.yml`, or the jar filename.

## Commands

The `/nebula` command surface is defined in
`nebula-plugin/src/main/java/org/nebula/plugin/NebulaCommand.java`. Frequently used
subcommands include:

| Command | Description |
|---------|-------------|
| `/nebula status` | Component counts, CAS store sizes, tracked positions |
| `/nebula scan` | Rescan loaded chunks for redstone components (needed for command/RCON-placed redstone) |
| `/nebula capture start [ticks]` / `stop` | Deterministic state capture to a timestamped `.nrp` |
| `/nebula perf [reset]` | DAG tick timing + microstep percentiles |
| `/nebula diag` | Per-invocation cascade diagnostics |
| `/nebula diff` | Paper differential comparison (single-thread oracle) |
| `/nebula dag-stats` | DAG build performance |
| `/nebula random` | Random-budget control |
| `/nebula fidelity` | Fidelity degradation control |
| `/nebula coverage` | RW-set annotation coverage from real bridge inventory |
| `/nebula survival` | Player subsystem toggle (shadow) |
| `/nebula help` | Command help |

Run `/nebula help` on your build for the authoritative list — the command set evolves.

## Project structure

The Gradle build defines 13 modules (see `settings.gradle.kts`):

- `nebula-core` — RW-set, DAG primitives, task scheduling, player state, VAP classes
- `nebula-guard-api` — runtime RW-set integrity guard API
- `nebula-agent` — Java agent (ASM bytecode instrumentation)
- `nebula-redstone` — redstone world state, actions, MicroStepScheduler
- `nebula-entity` — entity/block-entity/fluid/explosion/light/AI task factories
- `nebula-replay` — capture/replay + state hashing
- `nebula-folia-bridge` — Folia API abstractions, capture harness, tick hooks
- `nebula-folia-adapter` — NMS bridges (Java 25)
- `nebula-plugin` — Bukkit plugin entry point + commands
- `nebula-player` — player DAG executor (shadow)
- `nebula-maintenance` — annotation/patch tooling
- `nebula-bench`, `nebula-integration` — benchmarks and integration harnesses

The native fork lives under `nebula-server-build/` and is built separately.

## Testing

```bash
# Root module tests
./gradlew test

# A single module
./gradlew :nebula-plugin:test

# Include long-running (slow-tagged) determinism replays
./gradlew test -Pslow
```

Test totals are intentionally not frozen in this README — they drift. Run the relevant
module tasks for current results. Note that the latest recorded `nebula-player` report
includes failures (see PROJECT_STATUS.md); do not assume a globally green suite.

## License

MIT (see LICENSE file)

## Authors

NebulaArchTeam
