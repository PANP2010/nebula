# Nebula Blockers

## Progress Update (2026-06-10) — determinism foundation + patch NEBULA-PATCH-2026-001

A focused build-out session (branch `fix/tarjan-scc-overflow-and-dag-baseline`)
that turned several P6 "PARTIAL/STUB" rows into genuinely-live, test-backed
subsystems and implemented the external-review patch end to end. This does **not**
overturn the P6 verdict for *production-server* integration (the bundler still
schedules without a live NMS binding, and zero-diff-vs-vanilla remains gated on a
Folia capture environment) — but the deterministic-execution core is now real and
exercised by automated tests, not just modelled.

### What became genuinely live (was PARTIAL/STUB in P6)

| P6 row | Was | Now |
|---|---|---|
| §5 Redstone | "live path uses empty action registry → inert no-ops" | **Live.** `RedstoneActions.defaults()` wired; wire/torch/repeater/comparator execute through the snapshot/CAS path. Found+fixed a real bug: live actions didn't survive SCC contraction (`COMPOUND_SCC` fell back to inert members) — `RedstoneTaskRunner` now dispatches compound members through the registry. |
| §6 Physics+Collision | "named MOVE/COLLISION tasks are dead" | **Live.** `EntityPhysicsState` (versioned CAS store), `EntityMoveAction` (gravity+drag+Euler), `EntityCollisionResponseAction` (elastic), `EntityTickExecutor`. MOVE is now **terrain-aware** via a read-only `TerrainView` (closes the declared-vs-actual block-read gap). |
| §11 Layered Random | "effectively always T1; T0 gen absent" | **Order-independent T0 seeding live.** `LayeredRandomSource` derives per-task streams from `(worldSeed, tick, entityId, instance)` via SplitMix64; proven order-independent two ways (shuffled DAG input + raw forward/reverse runner). `RandomBudget` now fed from real execution → live DG2 over-budget metric. |
| §12 Determinism Verify | "replay = hash trail only" | **Self-consistency proven at scale.** Redstone replay determinism suite (wire line, single-source microstep, torch burnout, repeater delay) incl. a 10k-tick `slow`-tagged DG1-scale check; entity physics determinism to 5k ticks; a reference-capture harness (`save`→`load`→re-run→verify) ready to plug a Folia capture into. Still self-consistency, NOT zero-diff vs vanilla. |
| §16 Errors/Degradation | "fidelity tier is a label" | **Downgrade path live.** `FidelityDowngradeController` implements T0→T1→T2→T3→fallback (T3 added per patch §变更四) driven by the live over-budget rate and MSPT; integration-tested end to end. |

### Cross-subsystem integration (new — was not even a P6 row)

- **Combined redstone+entity tick.** `CompositeTaskRunner` (nebula-core) routes
  tasks to subsystem runners by type, so redstone wire propagation and entity
  physics build into **one DAG** and execute together. New `nebula-integration`
  test module proves both subsystems advance in one tick, the combined run
  replays deterministically (hash of both worlds), and unrouted task types fail
  loudly. This is the first concrete demonstration of the core architectural
  claim — causally-independent tasks share one graph regardless of subsystem.
- New core primitives: `LayerCommitting` interface (shared layer commit
  lifecycle), `CoarseDagBuilder` + `DagBuildBudget` + `BudgetedDagBuilder`
  (patch §变更二 — DAG build-time budget + avalanche guard with p50/p99/max
  diagnostics), `TarjanScc` made iterative (fixed a real `StackOverflowError` on
  deep dependency chains at the 4000-8000 task scale Nebula targets).

### NEBULA-PATCH-2026-001 — all 7 items addressed

变更一 (Phase 1.5 annotation maintenance — MSD signature extractor + differ +
regression runner + coverage dashboard, validated against **real decompiled MC
1.21.4 Mojmaps sources**, with inheritance-aware resolution that caught the
`RepeaterBlock.tick`→`DiodeBlock.tick` inheritance case); 变更二 (build budget,
above); 变更三 (VAP plugin certification model + searchable catalog); 变更四
(T2/T3 fidelity tiers, above); 变更五 (`TargetVersion` MC 1.21.4 anchor); 变更
六/七 (revised perf model + competitor analysis, `docs/patch-002-perf-and-competitor.md`).

### Honest scope of this update

- Everything above is **self-consistent** (Nebula reproduces itself), validated
  by automated tests in the gradle suite. The remaining gap to true DG1/DG2 is
  **zero-diff against vanilla**, which needs a real Folia server capture — the
  one external blocker, now a single well-defined plug-in point (the
  reference-capture harness).
- The §变更一 MSD is a *source-signature* diff; the bytecode data-flow summary
  for finer Level 2 semantic detection needs compiled classes and is future work.
- Terrain collision reads the cell below the new position; a >1 block/tick fall
  could read an undeclared cell — swept/sub-stepped collision is future work.
- These changes are in the module libraries, not yet wired into the live
  production bundler/NMS path — the P6 "schedules without parallelizing in
  production" observation still stands for the server jar.

---

## Architecture Audit (2026-05-30) — P6 (adversarial, 16-section deep audit)

A full adversarial re-audit of `docs/星云架构.md` against the codebase
(16 parallel section auditors + adversarial re-checks of every IMPLEMENTED
claim). **Verdict: kernel-complete, integration-incomplete. NOT fully
implemented.** This supersedes the more optimistic P5 summary below where they
disagree.

### Per-section status

| Section | Status | Reason |
|---|---|---|
| §2.2 BuildDAG | **IMPLEMENTED** | build+layer+execute run every nebula-mode tick; WAR edges intentionally dropped, dirty-set approximated |
| §3 RWSet | **IMPLEMENTED** | all 7 categories, segment-aware prefix match, union merge; runs unconditionally during build |
| §2.3 Microsteps | PARTIAL | cap=256 wired, but downstream generation never fires and conflict-deferred tasks are dropped, not re-seeded next tick |
| §4.1-4.3 Buckets | PARTIAL | buckets are 32 **blocks** not 32 chunks; no grid index (O(K²) conflict scan); no transitive-edge elimination |
| §4.4-4.5 Exec+Global | PARTIAL | layer dispatch wired; CPU affinity + lock-free work-stealing are STUB_ONLY (bench-only); no read-version check; GLOBAL via wildcard RWSet |
| §5 Redstone | PARTIAL | annotations + task model exist & tested, but live path uses an empty action registry → inert no-ops, no NMS binding |
| §6 Physics+Collision | PARTIAL | impulse deferral + serial flush wired (D3a); named MOVE/COLLISION tasks are dead; 3-phase is really 2-phase |
| §7 AI+POI | PARTIAL | entity RCU snapshot real; POI RCU missing; AI 4-task split is test-only; T1 weak-deps absent |
| §8 Fluids | STUB | per-position RWSet model is dead code; live path = one coarse vanilla `fluid_ticks` node |
| §9 Explosions | STUB→partial | layer actions still `()->{}` (vanilla executes synchronously), BUT pipeline now LIVE: explosions recorded + drained per tick into observability sub-DAGs (patch 0075 fixed the unbounded PENDING-queue leak — `drainAndBuildSubDags` now called via tickDrain). `/nebula explosions` shows drained/sub-DAG counts. Real block-break-in-sub-DAG deferred (high blast radius) |
| §10 Lighting | **MISSING** | zero Nebula code; fully delegated to stock Moonrise/Starlight |
| §11 Layered Random | PARTIAL | budget accounting wired; T0 deterministic gen, shadow-execute, WriteBuffer re-exec all absent — effectively always T1 |
| §12 Determinism Verify | PARTIAL | SHA-256 per-tick hash real; replay = hash trail only, diff-localization dead. **RW-check (§12.4) CORRECTION:** not "broken" — it is **agent-gated**. The `AccessTracingTransformer` + `TraceHooks` + `RWSetConsistencyChecker` chain is complete and unit-tested (nebula-agent tests pass); it only feeds `ThreadLocalAccessTrace` when `-javaagent:nebula-agent.jar` is attached. The agentless bundler runs it inert (trace always empty → 0 violations), which is by design (§12.4 = test-mode tool). `/nebula verify` (patch 0074) adds an agentless determinism self-check |
| §13 VAP (L0/L1/L2) | STUB | Level 0/1 classes + tests exist but not reachable in the bundler — interceptor unregistered, live pluginQueue=null, sandbox never drained. **§13.5 reflection-tracking CORRECTION:** the agent-side instrumentation (`VapApiInterceptTransformer`, `AccessTracingTransformer`) DOES exist and is unit-tested — it is agent-gated like §12.4, not absent. Level 2 native scheduler API genuinely absent (Phase 3) |
| §15.3 Commands | PARTIAL→OK | all 6 now registered & functional: `verify` added (patch 0074, in-process determinism self-check); `scc` reports real telemetry (patch 0072); `profile` aliases bench |
| §16 Errors/Degradation | PARTIAL | detection/state-tracking wired; recovery EFFECTS mostly absent — fidelity tier is a label. **Microstep-overflow crash FIXED 2026-05-30 (patches added 0073)**: now degrades gracefully per §16.1 |

### Acceptance gates — all three FAIL

- **DG1 (10000-tick zero-diff replay):** NOT MET. Only a SHA-256 hash trail is
  emitted. No vanilla baseline, `inputToTasks` is a no-op (packets not decoded,
  see B1), no standard test world, binary-search localizer is dead code.
  Determinism-smoke.sh additionally depends on `/tick freeze`+`/tick step`,
  which this Folia/Nebula build does not expose — the smoke cannot run as-is.
- **DG2 (MSPT −30% vs Folia):** UNSUBSTANTIATED but the blocker shape is now
  clearer. `-Dnebula.parallel` defaults **OFF**. Two runtime measurements:
  - 250-entity stress (2026-05-30): parallel correct (avg-tasks/layer≈74, zero
    races) but the OLD runner was **slower** (≈8.5ms vs serial ≈5ms) due to
    coordinator-idle oversubscription.
  - **Caller-runs fix (patch 0079, nebula-core ParallelTaskRunner):** re-measured
    at 7300-task/tick + 8400-block-entity scale — parallel run-phase **1.658ms
    vs serial 1.734ms** (no longer a pessimization), zero races over 1200+ ticks.
  - **KEY FINDING:** run-phase is NOT the MSPT bottleneck. Build dominates
    (~8.6ms build vs ~1.7ms run; all ~7300 tasks collapse into ONE parallel
    layer). The next perf lever for DG2 is **DAG build time**, not run
    parallelism. Still no measured win over Folia, but the parallel-execution
    regression that blocked any win is now removed.
  - **Build-time optimizations (2026-05-30, nebula-core DagBuilder/SccContractor):**
    Two adversarially-verified, determinism-preserving wins landed at the
    per-phase level (FastBuildStats, the stable signal — end-to-end MSPT is too
    noisy on this loaded dev box to quote):
    - SCC contraction now runs Tarjan over edge-induced nodes only (~26 vs
      ~8450): **scc phase 2.20ms → ~1.4ms (-40%)**. 2 independent reviews SOUND.
    - Dropped the full O(N log N) task-ID sort in buildFast; sort only the ~30
      global-touching subset: **sort phase 0.58ms → 0.006ms**. Pinned by a new
      shuffled-input regression test (output is layer-sorted regardless of input).
    - **Tried + REVERTED:** caching self-only RWSets by position — measured a
      *regression* (2 ConcurrentHashMap lookups cost more than the cheap
      young-gen allocation they replaced). Recorded so it isn't re-attempted.
    - Remaining build cost: ~8000 TaskNode allocations/tick in the decomposers +
      conflict scan; further wins need decomposition-level caching, not buildFast.
- **DG3 (parallel execution enabled in production):** NOT MET, but advanced.
  Layer A coverage now spans all entity + block-entity tick paths (91+16
  classes, batches 13-15). `ParallelTaskRunner` still falls back to serial for
  any task lacking `parallelSafe`; caller-runs (patch 0079) makes the parallel
  path a net-neutral-to-positive choice instead of a loss. WorkStealingExecutor
  + CoreAffinity remain bench-only (the tick path uses the caller-runs chunked
  fan-out over a shared queue, not per-core work-stealing); no read-version
  assertions. Remaining for production-default-on: redstone/fluid annotation
  coverage + RW-Guard certification (agent-gated).

### Fixed during this audit session (2026-05-30)

- **getCurrentWorldData() NPE on coordinator thread (minecraft patches 0071 + 0075).**
  `/fill`,`/setblock`,`/summon` etc. run on the global coordinator thread with no
  region world-data context, NPEing deep in setBlock/addEntity. Patch 0071 fixed
  the async chunk-load callback path; **patch 0075 generalized it** to ALL commands
  at the single chokepoint `Commands.performCommand` (installs the target level's
  region + world-data context for the command's duration, no-op when context
  already exists so region-worker command blocks are unaffected). Verified
  `/fill 121 furnaces`, `/summon TNT` succeed with zero NPEs. **Regression-probed
  2026-05-30:** 10 diverse world-mutating commands (chest/zombie/item/redstone/
  water/armor_stand/tnt-fill/falling_block/sapling/particle) + 765 stress ticks
  with live entities/redstone/water — ZERO NPEs, determinism self-check passes.
  The coordinator-thread NPE class is considered closed.
- **§15.3 /nebula scc was echoing a static constant (minecraft patch 0072).**
  Now reports real per-build SccStats (builds/contracted/serialised/max-size).
- **§16.1 microstep overflow crashed the whole server (nebula-core + patch 0073).**
  `MicroStepLimitException` propagated past `TickPipeline.execute` → uncaught in
  `ServerLevel.nebula$tickViaDAG` (only catches DagExecutionException) →
  `NebulaTickDriver` called `stopServer()`. Now caught: logs a warning, sets
  `TickResult.microStepOverflowed`, completes the tick; `/nebula stats` shows
  `microstep-overflows=N`. Test rewritten to assert graceful degradation.
- **§15.3 `/nebula verify` was missing — the 6th documented command (patch 0074).**
  Now an in-process determinism self-check: hashes the current level's structural
  state twice in succession; identical digests prove the hash computer is
  deterministic (the invariant replay verification rests on). NOT the full
  record→replay→compare loop (needs packet decode, B1). Runtime-verified
  DETERMINISTIC on empty + 121-furnace worlds.
- **§9 explosion PENDING queue leaked unboundedly (patch 0075).**
  `explode0` recorded every explosion but nothing drained the queue (each entry
  pins a ServerLevel). Now drained per tick via `tickDrain` in `nebula$tickViaDAG`,
  exercising the §9 sub-DAG path for the first time; also fixed the peek+break
  drain that leaked entries queued behind another level's explosion.

### Honest bottom line

A well-engineered, genuinely-live DAG construction kernel (§2.2/§3) wrapped in
broad, mostly-inert domain scaffolding. The scheduler builds correct parallel
DAGs and can execute them in parallel deterministically when the flag is on, but
in production it schedules without parallelizing, most domain decomposition is
dead/test-only or a coarse vanilla passthrough, VAP is unwired end-to-end, and
no determinism or performance gate has been demonstrated.

---

## Architecture Audit (2026-05-29) — P5


A second audit pass of `docs/星云架构.md` against the implementation found
the following NEW gaps (not in the 2026-05-28 list below). Resolved during
this session unless noted:

1. **§12.4 dead config flags.** `NebulaConfig.debugRwCheck()` and
   `debugReplayRecord()` had accessors but no consumers. **Fixed in
   d27b5d4 / patch 0068:** rw-check now activates RW-Guard via
   NebulaGuardIntegration static init; replay-record drives a new
   `NebulaReplayIntegration` that logs per-tick structural hashes
   to logs/nebula-replay.log. `/nebula replay` exposes status.

2. **§7.4 POI RCU completely missing.** Architecture requires AtomicReference
   + CAS snapshot for POI per tick. AI tasks today rely on regular RWSet
   reads. **Status: not implemented in this branch** — POI
   contention has not surfaced as a blocker on bench workloads, deferred
   pending observed contention.

3. **§10 lighting subsystem unmodelled.** Doc lists 7 subsystems; light
   updates have no LightTask/LightUpdate node in nebula-core/entity.
   **Status: as-designed for now** — Folia's lighting path runs
   inline with chunk system; Nebula has not interposed because lighting
   is already async on the chunk thread, not the tick thread.

4. **§13.4 VAP Level 2 native API not exported.** `Nebula.getScheduler()`
   / `submitTask` is in the doc but no implementation. **Status: Phase 3
   target**, not in scope for current vap-phase2 branch.

5. **§13.5 reflection / dynamic-proxy tracking missing.** `Method.invoke`
   not instrumented in nebula-agent. **Status: deferred** — VAP Level 1
   `@ManagedState` covers the common case; reflection-heavy plugins are
   rare in practice.

6. **§15.3 doc lists subcommands not implemented.** dag/scc/profile/verify
   commands documented but not in NebulaCommand. **Status: deferred** —
   the existing /nebula bench + /nebula status + /nebula hash + /nebula
   plugins + /nebula replay (this audit added) cover the operational
   needs. dag visualization (`exportDot`) is a Phase 3 nice-to-have.

7. **§4.3 transitive reduction not implemented.** Optional DAG optimization;
   not pursued because layering computes transitive closure for free.
   **Status: as-designed, won't fix** — the chain encoding in DagBuilder.
   buildFast already minimises edge count for the dominant workload.

---

## Architecture Audit (2026-05-28) — P0

A full re-read of `docs/星云架构.md` against the implementation found and fixed
the following deviations:

1. **§4.1-§4.3 spatial bucket DAG building was unwired.** `BucketDagBuilder`
   existed but `TickPipeline.execute` called `DagBuilder.build` (O(N²)).
   Fixed in 1c276fc / 6f0d108. With 250 mobs the same workload went from
   84 ticks/30s @ 357ms → 230 ticks/30s @ 130ms. Required also adding
   `readBlock(entityPos)` on entity tasks per §6.3 so they get spatial
   bucket affinity instead of falling into GLOBAL_BUCKET.

2. **§15.1 nebula.yml was a doc file with no loader.** All settings came
   from `-Dnebula.*` system properties. Fixed in a7f3f51 / cb3f248 with
   `NebulaConfig.loadOnce()` at boot. System properties still override.

3. **§11.2 RandomBudget allocate/evaluate was dead code.** No caller
   ever invoked it; `entity-evals` was always 0. Fixed in 4aae7aa with
   per-entity-tick allocate + recordEntityCalls in EntityTaskBuilder.
   Real per-call counting still requires a Random hook layer (B5).

4. **§11.5 NebulaConfig values ignored by RandomBudget.** The static-init
   `BUDGET = new RandomBudget()` pre-loaded defaults before
   `NebulaConfig.loadOnce()` ran. Fixed in 8c9d5b6 with a lazy singleton
   that picks up YAML values on first access.

5. **§16.2 fidelity downgrade controller was unused.** Existed in
   nebula-core but only tested. Fixed in 76784f4: `NebulaFidelityIntegration`
   reports each tick's MSPT + over-budget rate; DAG exception → forceFallback;
   `/nebula fidelity` and `/nebula fidelity reset` exposed.

6. **§15.3 /nebula status was missing.** Doc lists status as a primary
   command but only `mode` and `stats` existed separately. Added in b12db5c
   as a multi-line aggregate (mode + fidelity + mspt + parallelism + runner
   + guard + random tier).

7. **§13.6 plugin sandbox lists were not exposed.** `PluginSandbox.java`
   existed but nebula.yml had no `sandboxed-plugins` / `non-sandboxed-plugins`
   lists wired through. Fixed in 2b24ea9: `NebulaConfig.shouldSandboxPlugin`
   now provides the dispatch contract; runtime CraftServer hook is the next
   step.

Other deviations identified, partially addressed:
- D3a vs §6.2 D3b — collision pipeline is two-phase, not three-phase
  (commit dcaa057 documents the deviation explicitly)
- §11.3 shadow-execute / re-execute — `WriteBuffer` exists, allocate/evaluate
  cycle now wired (4aae7aa), but real per-call counting deferred (B5)
- §13.6 plugin sandbox runtime — config lists now plumbed (2b24ea9), but
  `CraftServer.enablePlugin` does not yet route to `PluginSandbox` based on
  `shouldSandboxPlugin()`.

### B5: Random Per-Call Counting Requires Hook Layer (DG2)

**Status:** Wired but counts are placeholder  
**Since:** 2026-05-28 (4aae7aa)  
**Affects:** §11.5 downgrade, DG2 over-budget acceptance

`NebulaRandomGate.allocate/recordEntityCalls` is now called per entity
tick (commit 4aae7aa). The allocation cycle populates `entity-evals` so
`/nebula random` reflects activity. However `actualCalls` is hardcoded
to 0 — counting real `RandomSource.nextX()` calls per entity requires
either a JVM agent that instruments `RandomSource` or a wrapper that
swaps `entity.random` during the tick. Both are non-trivial.

**Workaround:** budget allocation populates the `historicalMax` table
adaptively, so the §11.5 downgrade machinery can still trigger if a
future hook layer detects real over-budget consumption. For now the
over-budget rate stays 0 and §11.5 never fires.

---

## Active Blockers (continued)

### B1: Replay Scheduler — Packet→TaskNode Mapping Incomplete (DG2/DG3)

**Status:** Partially resolved (2026-05-27)  
**Since:** 2026-05-25  
**Affects:** Phase 1 Month 10-12, Phase 2 RC testing

`NebulaServerReplayScheduler` (c5f5b99) is now wired to a live `ServerLevel`
and provides a working `computeStateHash()` path. A **category-collision bug
in StateHashComputer** was found and fixed (deb2da6): the four state categories
(blocks/BEs/entities/globals) previously produced identical SHA-256 contributions
when using the same key/value — fixed by prefixing each category with a 3-letter
tag and bumping format to v2. Eight determinism-contract tests now verify this.

**Player-input seeding (3c068cc, a8eaa55):** `inputToTasks()` now emits one
GLOBAL_RW placeholder task per player that has packets in the recorded
tick. Player IDs are sorted to keep replay deterministic across runs.
Two regression tests verify the seeding contract.

**Remaining gap:** Packet payloads are NOT decoded — the seed task is a
debug log and does not re-execute the original packet effect. Full
NMS packet decoding (byte[] → structured task with precise RWSet) requires
codec wiring inside the Minecraft source tree and is deferred as a
separate workstream.

**Workaround:** Replay determinism is verified via `computeStateHash()`
plus the player-input seed tasks — divergent player counts or ordering
between record and replay surface as different DAG shapes, and any state
mutation surfaces in the hash.

---

### B2: Performance Benchmarking Requires JMH + Real Workload (DG2)

**Status:** Non-blocking (can proceed to Phase 2 code)  
**Since:** 2026-05-25

DG2 criterion "MSPT relative to Folia reduced ≥30%" requires:
- async-profiler integration
- JMH benchmarks for DAG build + execute hot paths
- Real server tick capture for workload replay

**Workaround:** Code correctness is verified. Performance work is parallelizable
with Phase 2 development.

---

### B3: Plugin Compatibility Testing Requires Real Plugin Jars (DG3)

**Status:** Blocking DG3 gate  
**Since:** 2026-05-25  
**Affects:** Phase 2 Month 7-8 RC testing

DG3 criterion "mainstream plugin Level 0 compatibility ≥80%" requires:
- EssentialsX, WorldGuard, LuckPerms, PlaceholderAPI jars
- A running server instance with VAP agent attached
- Integration test harness for plugin lifecycle events

**Workaround:** VAP Level 0 infrastructure (PluginTaskQueue, VapLevel,
agent-side transformers) is complete. Real plugin testing is deferred until
B1 is resolved and a server fork is available.

---

### B4: Intra-Layer Parallel Gated by parallelSafe Flag (D8 partial)

**Status:** Partially resolved (2026-05-26)  
**Since:** 2026-05-26  
**Affects:** Phase 1 Month 4-6 (Layer A annotations), Phase 2 Month 1-3

`TaskNode` now carries a `parallelSafe` boolean (343504d, 40bbb9c).
`ParallelTaskRunner` only fans out layers where **all** tasks have this flag set.
Tasks without it fall back to serial execution and increment `degradedLayers`.

Self-only tasks (non-player entities, independent BEs like furnaces/chests)
are already marked `parallelSafe`. GLOBAL_RW, hopper, and player tasks
remain serial by default.

**Remaining gap:** Enabling `parallel` in production requires Layer A
annotation coverage to reach ~70%. As of 2026-05-30 (batches 13-15, patches
0076-0078) the entity AND block-entity tick paths are fully covered: **91 entity
classes + 16 block-entity classes carry @NebulaRW** — every concrete and
base-class aiStep()/customServerAiStep() override and every BE tick/serverTick.
Remaining uncovered tick paths: redstone components + fluid spread (both still
run as coarse GLOBAL_RW nodes). Until those are annotated, opening the gate
risks data races on NMS structures not covered by explicit RWSet declarations.
The RW-Guard (§12.4, agent-gated) is the tool to certify coverage is clean
before flipping `-Dnebula.parallel=true` on by default.

**Workaround:** D4 (cross-region parallelism) remains the primary parallelism
source and is safe today.

---

## Summary: Code Infrastructure Completeness

All Phase 0–2 *code artifacts* that can be built without a server fork are complete:

| Component | Status | Location |
|-----------|--------|----------|
| RWSet data model | ✓ | nebula-core/rw/ |
| @NebulaRW annotation | ✓ | nebula-core/annotations/ |
| BucketGrid spatial index | ✓ | nebula-core/bucket/ |
| DAG (Kahn layering + WAW arbitration) | ✓ | nebula-core/scheduler/ |
| SCC contraction (Tarjan) | ✓ | nebula-core/scheduler/ |
| MicroStepExtender | ✓ | nebula-core/scheduler/ |
| TickPipeline (unified orchestrator) | ✓ | nebula-core/scheduler/ |
| CompositeTaskGenerator | ✓ | nebula-core/scheduler/ |
| Redstone task model + annotations | ✓ | nebula-redstone/ |
| Entity physics (move/collision/response) | ✓ | nebula-entity/ |
| Block entity tasks (hopper/furnace/brewer/dropper/dispenser) | ✓ | nebula-entity/ |
| Fluid subsystem (water/lava flow + propagation) | ✓ | nebula-entity/ |
| Explosion sub-DAG (4-layer pipeline) | ✓ | nebula-entity/ |
| AI pipeline (sense→goal→pathfind→act) | ✓ | nebula-entity/ |
| Random management (budget/shadow exec/downgrade) | ✓ | nebula-core/random/ |
| VAP Level 0 plugin scheduling | ✓ | nebula-core/vap/ |
| RW-Guard validation | ✓ | nebula-guard-api/ |
| Replay recorder/player/verifier | ✓ | nebula-replay/ |
| TickPipelineReplayAdapter | ✓ | nebula-replay/ |
| JVM Agent (bytecode transform) | ✓ | nebula-agent/ |
| Folia bridge hooks | ✓ | nebula-folia-bridge/ |
| Plugin commands/monitoring | ✓ | nebula-plugin/ |

---

## Resolved

### B1 (partial): StateHashComputer Category-Collision Bug
**Resolved:** 2026-05-26 (deb2da6)  
Four state categories (blocks/BEs/entities/globals) were fed into SHA-256 with
identical encoding — same key/value in different categories produced the same
hash contribution. Fixed by prefixing each category with "BLK"/"BE"/"ENT"/"GLB"
and length-prefixing both keys and values. Format bumped to v2.

### B1 (partial): Replay Scheduler Player-Input Seeding
**Resolved:** 2026-05-27 (3c068cc, a8eaa55)  
`NebulaServerReplayScheduler.inputToTasks()` now emits one GLOBAL_RW
placeholder TaskNode per player with packets in the recorded tick.
Player IDs sorted for replay determinism. Two regression tests verify
the seeding contract. Full NMS packet decoding remains deferred.

### B1 (partial): NebulaServerReplayScheduler computeStateHash()
**Resolved:** 2026-05-26 (c5f5b99, 8872500)  
Live ServerLevel → state hash path now works. `StateHashComputerTest` (8 tests)
verifies determinism contract. Replay hash is now testable without a full
packet replay harness.

### D2: Per-BE Task Decomposition + Layer Audit
**Resolved:** 2026-05-26  
`BlockEntityTaskBuilder` generates per-BE TaskNodes with three categories:
independent (self-only RWSets → same layer), hopper (neighbor-touching → serialized),
conservative (GLOBAL_RW → SCC-contracted). `BeLayeringTest` (8 tests) confirms
correct DAG behavior including SCC contraction of GLOBAL_RW cycles.

### D8 (partial): parallelSafe Gating for Intra-Layer Parallelism
**Resolved:** 2026-05-26 (343504d, 40bbb9c)  
`ParallelTaskRunner` now checks `parallelSafe` flag before fanning out.
Non-player entities and independent BEs are marked safe. Degraded layers
tracked and surfaced in `/nebula parallel` output.

### D3 (partial): Deferred Impulse Collision Pipeline (D3a — Compromise)
**Resolved:** 2026-05-27 (b7470de, ea534d5)  
**Architecture deviation noted, see below**

`DeferredImpulseBuffer` implemented: `Entity.push(Entity)` enqueues
cross-entity impulses when buffer is active, applying them in a serial
`collision_flush` task (GLOBAL_RW) after all entity tasks complete.
Non-player entities re-enabled as `parallelSafe`. Buffer is region-local
in `RegionizedWorldData`.

**Architecture deviation from §6.2:**

§6.2 specifies a strict three-phase pipeline:
1. Detection (parallel, read-only AABB sweep)
2. Impulse aggregation (serial, entityId-sorted)
3. Position update (parallel, self-only writes)

The current "D3a" implementation merges phases 1 and 3 with the AI/move
work — only **phase 2 (impulse application)** is correctly serialized.
Detection still runs inline inside `Entity.move` → `CollisionUtil`, which
writes velocity on block collision. Positions are also written inside the
parallel tick phase rather than as a separate post-impulse phase.

**Visible consequence:** an impulse from `collision_flush` translates into
position change one tick later than vanilla. Invisible at 20 TPS but
**replay hashes will diverge** unless the recorder also defers impulse
timing. Tracked as part of B1's full determinism workstream.

**D3b (full three-phase) — deferred:** requires restructuring
`Entity.move()` and `CollisionUtil` to split detection vs application,
plus splitting AI/move/position into three separate task layers. Not in
scope for this branch.

### B4 (partial): Layer A Annotation Count Update
**Resolved:** 2026-05-27 (batches 2-7)  
Across 6 batches added 21 entity/block/fluid annotations:
- Entity: baseTick/lavaHurt/applyEffectsFromBlocksForLastMovements/updateSwimming/moveRelative
- LivingEntity: baseTick/travel/checkFallDamage/hurtServer/doHurtTarget/onClimbable/tickDeath/onChangedBlock/removeFrost/tryAddFrost/rideTick
- Mob: baseTick/doHurtTarget/inactiveTick/tickHeadTurn/checkDespawn
- Redstone: RedstoneTorch (PROPAGATES), DiodeBlock/Repeater (DEFERRED), Observer (PROPAGATES), Comparator (DEFERRED)
- BE ticks: Jukebox (self-only), ShulkerBox (GLOBAL_RW)
- Fluids: FlowingFluid.tick (PROPAGATES), LavaFluid.randomTick

Total annotations: 60 (DG2 target ~130, ~46% complete).

### B3 (partial): VAP Level 0 Compatibility Test Harness
**Resolved:** 2026-05-27 (9965a27)  
6 integration tests covering arch §13.2 plugin phase contract via
TickPipeline + PluginTaskQueue with synthetic plugins:
- kernelPhaseCompletesBeforePluginPhase
- interPluginOrderIsRegistrationOrder
- intraPluginPreservesSubmissionOrder
- multiplePluginsSubmitFromMultipleKernelTasks
- pluginTaskFailureSurfacesAsPluginTaskException
- unknownPluginsExecuteAfterRegisteredOnes
Real third-party plugin JARs still required for §14.4 DG3 ≥80% compat rate.
