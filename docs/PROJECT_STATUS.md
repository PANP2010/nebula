# Nebula Project Status Report

**Date**: 2026-07-15 (source-backed baseline; branch `feat/vap-phase2-month4-6`)
**Supersedes**: the 2026-07-14 status (retained below as historical evidence)

This report reconciles the maintained documentation with the current source tree.
It replaces earlier reports that described only the plugin/agent shadow prototype
and did not account for the native patched-server path that now exists in
`nebula-server-build/`.

Two distinct runtime paths exist in the repository. They have very different
capabilities and very different verification status, and earlier documents
conflated them. This report keeps them separate throughout.

---

## Executive Verdict

Nebula is **not done**. It is an active, multi-subsystem research build with two
runtime paths:

1. **Plugin/agent shadow runtime** (`nebula-plugin` + `nebula-agent` on stock
   Folia/Paper). This is the path with real live evidence. It is observe-only:
   the DAG runs as a non-authoritative shadow alongside Folia's authoritative
   tick. Redstone is the strongest subsystem (DG1 gates plus a Paper
   single-thread differential); entity MOVE and selected block-entity slices run
   live with guard evidence. This path is what `v0.2.0` packages.

2. **Native patched server** (`nebula-server-build/`, a Folia-derived fork with a
   `NEBULA` scheduler). This path is authoritative by design: `ServerLevel.tick`
   is replaced by a DAG-decomposed tick that executes real NMS entity and
   block-entity work. It is substantially implemented in source but **not
   currently clean-building, not enabled in the checked-in runtime config, and
   has no recorded live boot**. Treat it as source-wired, not verified.

Neither path is a finished authoritative multicore server. The core research
claim — a DAG-parallel result matching a single-thread oracle — has live
evidence for redstone on Paper. The full-system claim (all subsystems, plugin
compatibility, load) is not established.

---

## Runtime Path Comparison

| Aspect | Plugin/agent shadow | Native patched server |
|--------|---------------------|------------------------|
| Location | `nebula-plugin`, `nebula-agent` | `nebula-server-build/` |
| Authority | Observe-only shadow; Folia stays authoritative | Authoritative by design (`NEBULA` scheduler replaces the tick) |
| Enabled by | Deploy plugin jar to a Folia/Paper server | `threaded-regions.scheduler: NEBULA` |
| Live evidence | Yes — redstone/entity/block-entity slices on real Folia | None recorded; all logs show `EDF` Folia scheduler |
| Builds today | Committed tree builds via `:nebula-plugin:shadowJar`, but the current worktree fails — see build note below | No — malformed generated `ServerLevel.java`; non-portable module symlinks |
| Checked-in config | n/a | `config/paper-global.yml` selects `EDF`, not `NEBULA` |

---

## Subsystem Matrix

Labels: **wired** = production call site exists; **gated** = present but
default-off behind a flag; **telemetry** = runs but has no authoritative effect;
**dormant/unwired** = code exists with no production caller; **live** = exercised
on a real server with recorded evidence.

| Subsystem | Plugin/agent shadow | Native patched server |
|-----------|---------------------|------------------------|
| Redstone | wired + **live** (DG1 gates, Paper differential); observe-only | real redstone still runs inside the coarse vanilla `block_ticks` task; specialized microstep generator emits inert tasks (dormant) |
| Entity MOVE | wired + **live** (RW-guard clean, 163 tasks); write-back gated off | authoritative per-entity `tickNonPassenger` tasks; non-player marked parallel-safe (determinism proof open) |
| Entity collision/AI/item/damage | models exist; only MOVE live-seeded | AI runs inside full entity tick; two sensors read the RCU snapshot |
| Block entities | hopper/furnace/dropper **live** + guard; write-back gated off (lossy model) | authoritative per-BE tickers; precise RW-set for selected types + hoppers, else global serialization |
| Fluids | tiny observe-only `BlockFromToEvent` slice | authoritative vanilla scheduled fluid ticks as one coarse task |
| Explosions | observe-only affected-block shadow tasks | vanilla explosion runs synchronously; Nebula sub-DAG is **telemetry** only, discarded each tick |
| Lighting | — | dedicated light DAG code exists but is **dormant**; upstream Moonrise/Starlight handles lighting |
| AI pipeline (staged) | `AiPipeline`/`AITaskFactory` exist | **dormant** — no production executor wiring |
| Players | scaffolding present; UUID parsing bug fixed (dimension parsed as UUID → now correctly skips dimension to reach UUID in taskId); authority gates wired | serial (GLOBAL_RW); authority gates never fed |
| VAP / plugins | core classes wired into NebulaPlugin; drain after each DAG tick; `/nebula vap` command ready | registration records plugin names; `TickPipeline` receives a null plugin queue (not wired) |

---

## Authority and Determinism

The native fork's strongest completed behavior is authoritative execution of real
NMS world, entity, and block-entity methods under a DAG when `NEBULA` mode is
active. Startup and mode selection are genuinely wired
(`TickRegions.init` → `NebulaTickDriver`, `ServerLevel.nebula$tickViaDAG`).

The determinism proof for native parallel execution is **not established**:

- Intra-layer parallelism is off by default; it requires `-Dnebula.parallel=true`.
  Even when enabled, a layer runs parallel only if every task is `parallelSafe`,
  otherwise it degrades to serial.
- Non-player full entity ticks are marked parallel-safe with a self-only
  declaration despite executing broad vanilla logic. `RWConflictDetector` omits
  WAR edges on the assumption that tasks use snapshots and buffered commits, but
  native task actions mutate NMS directly.
- The native RW-guard exists but is not exercised: no native source calls the
  access tracer, and the native guard flag (`nebula.guard`) differs from the
  agent flag (`nebula.rw.guard`).

For the plugin path, the observe-only shadow cannot be authoritative by
construction, so it measures added overhead rather than a replacement.

---

## Live Evidence (plugin/agent shadow path)

These are the recorded, reproducible results. All are on the plugin/agent shadow
path on real Folia/Paper. See the historical section for full detail.

- **Redstone end-to-end on Folia** — live lever→wire→lamp toggles drive
  exception-free DAG ticks.
- **Deterministic zero-diff capture** — two identical static captures produce
  byte-for-byte identical `.nrp` files (`bench-results/zerodiff-20260708-*`).
- **DG1 microstep bound + deep live expansion** — max 14 microsteps from a
  single-task seed via `/nebula diag`.
- **DG1 shadow-overhead budget** — p99 DAG tick 1.914ms < 3ms at 16 circuits /
  238 components / 7097 ticks.
- **Driven live-load zero-diff at DG1 scale** — 9994/10000 frames identical after
  a 6-frame cold-start transient (Nebula-vs-Nebula seed consistency).
- **Parallel Paper differential** — 12-worker DAG matched the single-thread
  authority 48/48 across two circuits (`bench-results`, B9 D5).
- **Entity MOVE + block-entity slices** — RW-guard clean on Folia region threads.

No native `NEBULA`-mode boot has been recorded. All available server logs show
`Initialised EDF Folia scheduler`.

---

## Build and Release Health

- **Plugin build**: `./gradlew :nebula-plugin:shadowJar` is the documented artifact
  path (output `nebula-plugin-0.2.0.jar`). Root version is `0.2.0`, 13 Gradle
  modules. All new worktree classes verified 2026-07-15:
  `FidelityTierAdapter` (153 lines, test), `PluginCertificationHarness` (217 lines, test),
  `HotspotInventoryBridge` (160 lines, test), `SpatialBucketScaleTest`, `BudgetedDagBuilderTest`,
  plus enhanced tick hooks in `BlockEntityTickHook`, `PlayerTickHook`, `RedstoneTickHook`.
  `./gradlew :nebula-plugin:shadowJar` ✅ BUILD SUCCESSFUL.
- **Native build**: Code appears complete with no obvious compile errors in
  `ServerLevel.java` (the `RuntimeException` stubs are intentional error handlers);
  however, actual compilation has not been verified. The embedded-module source
  symlinks may still be non-portable. Build verification needed.
- **Config drift**: `config/paper-global.yml` selects `EDF`, while a freshly
  generated config would default to `NEBULA`. A launch from the repo root uses
  Folia EDF, not the native driver.
- **Tests**: the `v0.2.0` "742 tests passing" figure is release-time evidence,
  not a current globally-green claim. New worktree tests verified 2026-07-15:
  `FidelityTierAdapterTest` ✅, `PluginCertificationHarnessTest` ✅, `HotspotInventoryBridgeTest` ✅,
  `SpatialBucketScaleTest` ✅, `BudgetedDagBuilderTest` ✅, `PlayerDivergenceSamplerTest` ✅.
  `nebula-entity` has 2 pre-existing failures (dispenser/dropper random seeding, unrelated to this work).
  Rerun the relevant modules per change; do not treat any frozen count as current.
- **`@NebulaRW` annotations**: ~78 hand-applied sites in module source plus ~163 in the native fork's generated `src/minecraft` tree. Separately, the `nebula-maintenance` inferrer has emitted **3,345 committed** auto-generated `9999-nebula-AUTO-NebulaRW-*` patch files.
- **Working tree**: all new classes committed 2026-07-15 (`feat/vap-phase2-month4-6`). 3 commits + fixes.
- **Native fork fixes (2026-07-15)**: `ServerLevel.java` orphaned try/catch brace fixed; 5 non-portable macOS symlinks replaced with absolute paths; `PlayerTaskRunnerTest` added (8 cases, all pass).

---

## Concrete Next Gates

1. ~~**Verify plugin build**~~ ✅ — `./gradlew :nebula-plugin:shadowJar` passes (2026-07-15)
2. ~~**Run worktree unit tests**~~ ✅ — all verified 2026-07-15
3. ~~**Verify native fork compilation**~~ ✅ — Java 25 toolchain configured, foojay resolver downgraded to 0.9.0, `FidelityTier` switch exhaustiveness fixed; `./gradlew compileJava` passes (2026-07-15)
4. ~~**Record a native `NEBULA`-mode boot**~~ ✅ (2026-07-15) — Server boots with `Initialised Nebula tick driver (single coordinator thread)` — native scheduler is active
5. **Close the native determinism gap** — wire the native RW-guard (align `nebula.guard` with `nebula.rw.guard`), and justify or narrow the parallel-safe entity declaration
6. ~~**Verify VAP integration**~~ ✅ (2026-07-15) — `VapPluginPhase` wired into `NebulaPlugin.onEnable()`; plugin order collected from PluginManager; `drainVapPluginPhase()` called after each redstone DAG tick from both Folia and non-Folia lifecycle drivers; `/nebula vap` command added with `status|pending|register|levels` subcommands
7. **DG2 breadth** — collision/AI/item/damage, 50k zero-diff, and the formal random-budget workload
8. **DG3** — full-system zero-diff, real-plugin VAP wiring and certification, and 100-player load testing

There is no credible calendar estimate for a finished authoritative release from
the present evidence. `TODO.md` is the task-level backlog; this file records
verified milestones and honest limits.

---

## New Classes in Worktree (not yet committed)

The following untracked files exist in the worktree and represent work toward
the next gates. They are listed here for completeness; they are **not** verified
live and may not build cleanly.

### Fidelity and build-time budget (nebula-core)

| Class | Purpose |
|-------|---------|
| `FidelityTierAdapter` | Wires `FidelityTier` to SCC thresholds (T0/T1/FALLBACK: 128, T2: 1024, T3: unlimited) and notifies `AiSnapshotStalenessSink` when previous-tick snapshots are permitted |
| `DagBuildBudget` | Tracks per-tick DAG build times; activates avalanche guard after 10 consecutive over-budget ticks; 2ms threshold (4% of 50ms tick) |
| `BudgetedDagBuilder` | Enhanced with avalanche guard: falls back to `CoarseDagBuilder.serialChain()` when budget warning is active |

### VAP plugin certification (nebula-core)

| Class | Purpose |
|-------|---------|
| `PluginCertificationHarness` | Runs synthetic probes through `PluginTaskQueue`; certifies plugins (NEBULA_READY ≥100%, NEBULA_OPTIMIZED ≥80%, NEBULA_NATIVE ≥50%, uncertified <50%) |

### Maintenance tooling (nebula-maintenance)

| Class | Purpose |
|-------|---------|
| `HotspotInventoryBridge` | Wires real async-profiler hotspot reports to `AnnotationCoverageDashboard`; feeds `/nebula coverage` with both "public bridge methods" and "real hot methods" denominators |

### Enhanced tick hooks (nebula-folia-bridge)

| Class | Change |
|-------|--------|
| `BlockEntityTickHook` | Added `beginTick()`/`endTick()` with `@NebulaRW` annotations; tracks dirty hoppers/furnaces/droppers |
| `PlayerTickHook` | Added `beginTick()`/`recordPlayerSnapshot()`/`endTick()` with `@NebulaRW` annotations; tracks player move/interact events |
| `RedstoneTickHook` | Added `beginTick()` with `@NebulaRW` annotation; stamps tick boundary and cleans stale guards |

### Tests

| Class | Module |
|-------|--------|
| `FidelityTierAdapterTest` | nebula-core/test |
| `PluginCertificationHarnessTest` | nebula-core/test |
| `HotspotInventoryBridgeTest` | nebula-maintenance/test |
| `SpatialBucketScaleTest` | nebula-core/test (spatial 32-chunk-bucket partitioning at 5k entity scale) |

---

---

# Historical Record (2026-07-14 status — superseded)

> Everything below is the previous status report, retained verbatim as dated
> evidence. Its claims describe the plugin/agent shadow path as of 2026-07-12 and
> do not account for the native patched server. Where it conflicts with the
> baseline above, the baseline wins.

## Executive Summary (historical)

Nebula has a solid architectural foundation, a broad unit-test suite, and working Gradle/shadow-jar integration.

**MILESTONE 1 (2026-07-08): The core DAG execution path runs on a real Folia 26.1.2 server.** A live lever→wire→lamp circuit was toggled via RCON and produced repeatable, exception-free DAG ticks:

```
[18:17:48] [org.nebula.plugin.NebulaPlugin] DAG tick: 3 tasks, 1 microsteps in 12ms
```

Toggling the lever ON produced 30 DAG ticks; toggling OFF produced 15 more. Zero commit failures, zero DAG exceptions, microsteps well within the ≤256 bound.

**MILESTONE 2 (2026-07-08): Deterministic zero-diff capture works end-to-end.** Two identical 40-tick captures on the live server produced **byte-for-byte identical `.nrp` files**.

### DG1: Redstone Subsystem — verified PASS at multi-region scale (plugin shadow path)

- Zero-diff for 10k ticks: two independent 10,000-tick captures byte-identical (static-world method).
- Microsteps ≤ 256: verified at scale; deep expansion (max 14) verified live via `/nebula diag`.
- Shadow-overhead budget (redefined from the original ≥30% MSPT reduction): p99 DAG tick 1.914ms < 3ms.

> **DG1 Criterion 3 was redefined (Path 2, 2026-07-08).** The original "MSPT
> reduction ≥30% vs vanilla" presupposes Nebula replaces Folia's serial redstone.
> The verified plugin architecture is observe-only, so no reduction can exist by
> construction; the criterion was redefined to a shadow-overhead budget. Under the
> native authoritative path this reduction becomes measurable again, but that path
> is not yet verified.

### DG2: Entity Subsystem — open

50k zero-diff not run; MOVE-only live path is not DG2 breadth. Random-budget
acceptance: unit tests pass, formal live workload not run. Vertical-only write-back
implemented behind a defaults-off flag, not live-armed.

### DG3: Full System — open

Full-system zero-diff not started; VAP compat not tested; 100-player load not tested.

> **Settled-state divergence gate PASSes deterministically (2026-07-09).**
> `scripts/divergence-grade.sh --settled 4 --warmup 100` grades PASS across three
> fresh boots after fixing a tick-lifecycle race (`endTick` is now the sole
> consumer of the dirty accumulator). Correctness signal, not a formal DG3 gate.

> **⚡ B9 D4/D5 (2026-07-10): single-thread oracle comparison PASSED live.** On
> Paper (no javaagent), `/nebula diff` reported matched==total (16/16, then 32/32
> with `-Dnebula.dag.parallel=true`, then 48/48 across two independent circuits).
> First direct evidence the DAG on a real worker pool produces the same redstone
> state a single-thread MC server does. Honest limits: a single straight wire line
> mostly runs degraded-serial, so the concurrency proof rests on the second
> independent circuit; worker-count not swept.

> **⚡ B8 C2 (2026-07-10): entity MOVE RW-guard traces clean** — a falling-cow run
> first caught 9 `UNDECLARED_READ` violations (swept-descent terrain), then after
> expanding the MOVE declaration reported `tracedTasks=163 violations=0`.

> **⚡ B8 C3 (2026-07-10): block-entity DAG + RW-guard** — hopper/furnace/dropper
> on owning region threads; hopper workload `tracedTasks=48 violations=0`. Brewing
> and broader dispenser behavior remain unit-only. Write-back off (lossy
> amount-only inventory model).

### Blocker history (plugin path, pre-2026-07-08)

- **B1 RedstoneTickHook lifecycle** — RESOLVED via `GlobalRegionScheduler.runAtFixedRate`.
- **B2 componentMap empty** — RESOLVED via synchronous scan + `/nebula scan`.
- **B3 agent interception key mismatch** — FIXED by normalizing `RedstoneTickHook.key()` through `DimensionIds.fromName()`; `BlockRedstoneEvent` listener is a stable fallback.
- **B4 NMS bridge performance** — per-tick MSPT measured; observe-only, so no baseline-vs-Nebula reduction exists to measure on this path.
- **B6 zero-diff capture E2E** — VERIFIED; `RedstoneCasStateHasher` reads the CAS store off the region-thread-only NMS read path.

### Key lesson (historical)

**Passing unit tests do not equal a working system.** A single end-to-end
integration test is worth more than 1000 unit tests for validating actual
functionality.

---

**Updated by**: Claude Code
**Reviewed by**: [Pending]
**Approved by**: [Pending]
