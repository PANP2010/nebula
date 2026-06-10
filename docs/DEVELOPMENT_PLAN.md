# Nebula Development Plan

Date: 2026-06-09

## Current status

Nebula is kernel-complete and integration-incomplete.

The root Gradle project builds and tests successfully after local build bootstrap fixes, but the project is not product-complete. The DAG/RWSet scheduler kernel is the strongest part of the codebase. The remaining risk is in live Minecraft integration, deterministic replay, redstone behavior completeness, Folia/Nebula performance proof, and VAP/plugin compatibility.

Verified baseline command:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew test
```

Latest observed result: `BUILD SUCCESSFUL`, 29 actionable tasks executed.

## Completion assessment

| Area | Status | Notes |
|---|---:|---|
| Build/test bootstrap | Good | Root tests pass with Java 21; wrapper reliability was improved. |
| DAG/RWSet core | High | Core primitives, conflict detection, SCC contraction, and execution pipeline exist. |
| DAG build performance | Medium | Prior profiling shows DAG build/conflict scan dominates cost. |
| Runtime guard / agent | Medium | Guard API and agent skeleton exist, but full production integration remains incomplete. |
| Redstone Phase 0 | Partial | Scheduling/state snapshot pieces exist; live component behavior and replay proof remain incomplete. |
| Replay determinism | Low-medium | Replay module exists, but DG1/DG2 zero-diff gates are not proven. |
| Folia bridge/integration | Low-medium | Lightweight bridge exists; Java 25 Folia adapter is disabled. |
| Entity/physics/AI/fluids/explosions | Partial | Some generators/snapshots exist; several subsystems are partial or stubbed. |
| VAP/plugin compatibility | Low | Compatibility layer is still early/stub-level. |
| Production readiness | Low | DG1, DG2, and DG3 are not met. |

## Main development objective

Move the project from a passing kernel prototype to a measurable Phase 0 redstone validation candidate.

The next credible success target is DG1:

- 10,000+ tick redstone replay with zero diff.
- No silent divergence from microstep cap behavior.
- Redstone-heavy workload performance evidence.

## Milestone 0 — Lock the build and test baseline

Goal: make the current passing build reproducible for future work.

Tasks:

1. Document the current build baseline as Java 21 for the included root modules.
2. Preserve the Gradle wrapper reliability changes:
   - official Gradle distribution URL,
   - longer network timeout,
   - retry count/backoff,
   - executable `gradlew`.
3. Keep the disabled `nebula-folia-adapter` status explicit because it requires Java 25/Folia API.
4. Run the full root test suite before and after each implementation milestone.

Acceptance criteria:

- A clean checkout can run root tests with Java 21.
- README/build docs do not claim the active root build requires Gradle 9.5.1/Java 25 unless specifically referring to Paper/Folia source targets.
- `./gradlew test` passes when launched with Java 21.

## Milestone 1 — Establish DAG build benchmark baselines

Goal: turn the known DAG build bottleneck into a repeatable measurement.

Tasks:

1. Inspect existing `nebula-bench` JMH coverage.
2. Add or refine benchmarks for:
   - 1k/4k/8k task DAG builds,
   - redstone-like spatial workloads,
   - entity-only workloads,
   - mostly self-only RWSet workloads,
   - mixed read/write conflict-heavy workloads.
3. Capture baseline metrics for:
   - DAG build time,
   - conflict edge count,
   - task allocation count where available,
   - `RWConflictDetector.edgesFor` cost.
4. Add benchmark notes under `docs/profiling/`.

Acceptance criteria:

- A developer can run the same benchmark suite repeatedly.
- Baseline numbers are recorded before optimization.
- Benchmarks cover the workload patterns mentioned in current profiling docs.

## Milestone 2 — Reduce DAG build/conflict scan overhead

Goal: improve DAG construction without regressing correctness.

Candidate optimizations, in priority order:

1. Cache `RWSet.hashCode()` or equivalent immutable conflict key data for unequal-set short-circuiting.
2. Skip redundant RAW/WAW symmetric checks for self-only tasks when semantics allow it.
3. Pre-index entity-only tasks by entity ID.
4. Reduce avoidable `TaskNode` allocation churn during repeated tick-like builds.

Constraints:

- Do not reintroduce the reverted ConcurrentHashMap self-only RWSet cache approach unless benchmarks prove it helps.
- Every fast path needs regression tests for RAW, WAR, WAW, and no-conflict cases.
- Correctness beats benchmark improvement.

Acceptance criteria:

- Existing tests pass.
- New conflict-detection tests pass.
- JMH shows a measurable DAG build improvement on at least one target workload without broad regressions.

## Milestone 3 — Make redstone actions live one component at a time

Goal: convert redstone Phase 0 from scheduling/snapshot scaffolding into executable behavior.

Initial component order:

1. Redstone wire.
2. Repeater.
3. Torch.
4. Comparator.

Tasks per component:

1. Identify current action factory/generator/snapshot behavior.
2. Implement live state read/write through the existing snapshot model.
3. Add deterministic unit tests for stable simple circuits.
4. Add conflict/RWSet tests for adjacent updates.
5. Add replay-style tests if existing harness supports it.

Acceptance criteria:

- At least one real redstone component updates state through Nebula’s action path.
- Component tests are deterministic.
- RWSet declarations match actual reads/writes enforced by guard/tracing where applicable.

## Milestone 4 — Harden microstep semantics

Goal: prevent redstone divergence from ordering and microstep cap behavior.

Tasks:

1. Audit `MicroStepScheduler` and `MicroStepExtender` behavior.
2. Define what happens when the microstep cap is reached:
   - explicit warning/degradation, or
   - deterministic abort/fallback,
   - never silent divergence.
3. Add tests for oscillators, chained updates, and cap boundary behavior.
4. Confirm deterministic ordering for same-tick redstone updates.

Acceptance criteria:

- Cap behavior is visible and deterministic.
- Test circuits do not silently diverge.
- Ordering semantics are documented in code/tests or development docs.

## Milestone 5 — Minimum viable DG1 replay

Goal: create the first credible DG1 validation harness.

Tasks:

1. Define a small redstone stress world or deterministic synthetic equivalent.
2. Record reference output for 10,000 ticks.
3. Run Nebula output against the reference.
4. Produce a diff report format that identifies block position/state/tick mismatches.
5. Add a shorter replay smoke test for CI/local development if full 10,000 ticks is too heavy.

Acceptance criteria:

- Replay harness can compare reference vs Nebula outputs.
- At least one redstone scenario runs with zero diff for a meaningful tick count.
- Known failing cases are documented with exact subsystem owners.

## Milestone 6 — Re-run Folia vs Nebula benchmarks

Goal: update the performance story after DAG/redstone work.

Tasks:

1. Reproduce the previous Folia-vs-Nebula measurement setup where possible.
2. Include average MSPT, p95, p99, max, and sample count.
3. Add a 100 fake players / multi-dimension workload if available.
4. Separate DAG build cost from run-phase execution cost.
5. Compare against prior baseline: Nebula average slower but tail latency better.

Acceptance criteria:

- Performance docs explain whether Nebula is improving average MSPT, tail latency, or both.
- DG2 claims are not made unless the data supports them.

## Milestone 7 — Choose the next branch after DG1 evidence

After DG1 evidence exists, choose one branch:

1. Phase 0 hardening: deepen redstone correctness and replay coverage.
2. Phase 1 entity/physics: expand deterministic entity and collision pipelines.
3. VAP/plugin compatibility: move plugin sandbox/managed state from stub to usable prototype.

Decision inputs:

- Redstone replay stability.
- DAG build performance after optimizations.
- Folia/Nebula benchmark trend.
- Plugin compatibility risk.

## Immediate next tasks

1. Update build documentation so the active root build says Java 21 + Gradle 8.13.
2. Keep Java 25 requirements scoped to Paper/Folia upstream or disabled adapter work.
3. Verify `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew test` still passes.
4. Inspect `nebula-bench` and existing DAG benchmark coverage.
5. Add or refine DAG build benchmark baselines.

## Progress log

### 2026-06-09

- **Milestone 0 — done.** README updated to Java 21 / Gradle 8.13 active build;
  wrapper reliability fixes preserved; full test suite passes (29 tasks).
- **Milestone 1 — done.** `DagBuildBenchmark` extended with `entityOnly` and
  `conflictHeavy` workloads and task counts up to 8000; `-Pdagbaseline` focused
  JMH config added. Baseline recorded in
  `docs/profiling/dag-build-baseline-2026-06-09.md`.
- **Milestone 2 — substantially done (robustness over speed).**
  - Found and fixed a `StackOverflowError` in `TarjanScc.strongConnect`:
    converted recursive DFS to an iterative explicit-work-stack version with
    identical semantics. Added 20k-node deep-chain/deep-cycle regression tests.
    This was crashing DAG builds at the 4000–8000 task scale the project targets.
  - Baseline shows the realistic `redstone` (spatial) path is already ~linear
    and ~77× faster under `BucketDagBuilder` than the quadratic builder at 8000
    tasks — no speculative conflict-scan micro-optimization warranted.
  - Documented a latent `entityOnly` GLOBAL_BUCKET O(N²) cliff (position-less
    entity tasks bypass spatial buckets). Deferred a fix because realistic
    decomposer entity tasks carry block affinity and avoid it; any change to the
    global-ordering scan needs its own before/after benchmark per the blocker
    doc's reverted-cache warning.
  - Full suite green after all changes.
- **Next: Milestone 3** — make redstone actions live, starting with redstone
  wire (see milestone section above).

### 2026-06-09 (continued)

- **Milestone 3 — wire is live, with a real bug found and fixed.** The
  redstone action classes (wire/torch/repeater/comparator) already contained
  real logic and committed through the snapshot/CAS path, so the gap was
  elsewhere. Building the first end-to-end determinism test (below) surfaced it:
  **live redstone actions did not survive SCC contraction.** A wire line forms
  RAW cycles on neighbouring blocks plus a shared `REGION_SHOULD_SIGNAL` global,
  so `SccContractor` merges the whole line into one `COMPOUND_SCC` task. The
  runner had no registry entry for `COMPOUND_SCC` and fell back to the
  members' inert built-in actions, so `RedstoneWireAction` never ran and the
  simulation stalled. Fixed `RedstoneTaskRunner` to dispatch a compound's
  members through the action registry (each with its own snapshot, in
  deterministic ID order). Full suite stays green.
- **Milestone 5 — DG1 scaffold landed.** Added
  `RedstoneReplayDeterminismTest`: drives a 12-wire line + power source through
  `MicroStepScheduler` with live actions for 200 ticks, hashes world state each
  tick via the real `StateHashComputer`/`ReplayRecorder`, runs the scenario
  twice, and asserts the per-tick hash sequences match bit-for-bit via
  `ReplayVerifier`. It also asserts liveness (state actually changes), which is
  what caught the SCC-contraction bug above. This is a small, fast proxy for
  the real DG1 gate (10k-tick zero-diff replay); scaling the tick count and
  adding more circuit shapes (torch oscillator, repeater delay) is the
  remaining work toward DG1.
- **Next:** broaden DG1 coverage — a self-oscillating torch circuit (exercises
  the microstep cap and ordering) and a repeater-delay line (exercises DEFERRED
  components across ticks), then push the wire-line tick count toward the DG1
  target.

### 2026-06-09 (DG1 coverage expansion)

- **Determinism suite broadened to three circuit shapes**, all passing:
  1. *Wire line, re-seeded each tick* (original) — convergence + steady state.
  2. *Single-source microstep propagation* — dirty only the wire next to the
     source and assert the signal travels the whole line **in one tick** with
     exact per-block decay (14, 13, 12, …). Confirms the change-aware microstep
     expansion works end-to-end with live actions, and that a single-seeded
     frontier stays a singleton task (it does NOT get trapped in an inert
     compound the way a whole line submitted together would).
  3. *Torch + wire feedback loop* — a NOT-gate that oscillates, accumulates
     toggle count in internal state, and burns out. Asserts the
     internal-state-heavy oscillator replays deterministically and settles into
     a reproducible burned-out steady state (power 0).
- These pin down three things empirically: live actions survive SCC
  contraction, intra-tick microstep propagation is correct and deterministic,
  and per-position internal state (torch burnout) is reproducible across runs.
- **Next:** add a repeater-delay line (DEFERRED propagation across ticks), then
  scale the wire-line tick count from 200 toward the 10k DG1 target and measure
  wall-clock so the full-scale replay can run in CI or as a tagged slow test.

### 2026-06-09 (DG1-scale self-consistency)

- **First DG1-scale evidence.** Added `dg1ScaleWireLineReplaysDeterministically`
  (JUnit tag `slow`, excluded by default; run with `./gradlew test -Pslow`):
  10,000 ticks × 2 independent runs of a 16-wire line produce **identical
  per-tick state-hash sequences**. Wall-clock ≈ 3m (20k `executeTick` calls),
  which is why it is opt-in. Root `build.gradle.kts` now excludes the `slow`
  tag unless `-Pslow` is set, so the default suite stays fast.
- **Honest scope of this result.** This proves Nebula is *self-consistent*
  (bit-for-bit reproducible across its own runs at DG1 scale) — it is **not**
  yet a zero-diff comparison against a vanilla/Folia reference replay, which the
  full DG1 gate ultimately requires. Capturing that reference needs a real
  server environment (a known blocker in `docs/NEBULA_BLOCKERS.md`). Self-
  consistency is a necessary precondition for DG1 and the right thing to lock
  down first: a simulator that can't reproduce itself can never match a
  reference.
- **Next:** (1) repeater-delay line for cross-tick DEFERRED propagation;
  (2) richer circuits (mixed components, scheduled player inputs via the
  `ReplayRecorder` input path); (3) a reference-capture harness once a Folia
  test server is available, to turn self-consistency into true zero-diff DG1.

### 2026-06-09 (cross-tick deferred + reference-capture harness)

- **Repeater-delay line (DEFERRED cross-tick).** Added
  `repeaterDelayLineReplaysDeterministically` to the determinism suite: a
  repeater (delay 2) is driven by an input that goes high then low, so it must
  latch both a rising and a falling edge through its internal delay counter
  across ticks. Confirms cross-tick DEFERRED propagation (internal state
  surviving between ticks) replays deterministically. The fast suite now covers
  four circuit shapes: wire line, single-source microstep, torch burnout, and
  repeater delay.
- **Reference-capture harness landed.** New `RedstoneReferenceReplayTest`:
  - Records a redstone run to a `.nrpl` file via `ReplayRecorder.save`, loads
    it back with `ReplayPlayer.load`, runs the scenario fresh, and verifies the
    fresh run reproduces the saved per-tick hashes. This exercises the on-disk
    binary save/load round-trip end to end (previously only unit-tested in
    isolation) and is the reusable scaffold the real DG1 gate plugs into.
  - A second test tampers with one hash in the reference and asserts the
    verifier flags exactly one mismatch — guarding against a silently-passing
    broken capture.
  - **The harness is reference-source-agnostic by design:** today the reference
    is captured from Nebula itself; when a Folia test server is available the
    same `.nrpl` file can be captured from vanilla output instead, turning this
    into true zero-diff DG1 with no harness changes.
- **Next:** (1) richer mixed-component circuits and scheduled player inputs via
  the `ReplayRecorder` input path; (2) wire the harness to a real Folia capture
  once that environment exists (the remaining external blocker); (3) begin
  Milestone 6 (re-run Folia vs Nebula benchmarks) or branch into Phase 1
  entity/physics per Milestone 7.

### 2026-06-09 (Milestone 7 — entity physics made live)

- **The Phase 1 gap was the mirror of the redstone one.** `nebula-entity` had
  all the scheduling scaffolding (RW-set templates, task generation, microstep
  propagation) but **no executable state model** — every entity task was inert
  (`moveInert`, `collisionInert`), so entity physics could not run end to end
  or be replay-verified. Built the executable layer following the proven
  redstone pattern:
  - `EntityPhysicsState` — versioned, CAS-committed field store keyed by the
    same `EntityField` coordinates the RW-sets declare (mirrors
    `RedstoneWorldState`).
  - `EntityStateSnapshot` / `EntityTaskContext` — per-task versioned-read +
    buffered-write with stale-read detection.
  - `EntityTaskRunner` — dispatches actions per task, **including the
    SCC-compound member dispatch** so live behaviour survives contraction (the
    same fix applied to the redstone runner; entity collision pairs contract
    into compounds too).
  - `EntityTickExecutor` — builds the conflict DAG, runs layers, CAS-commits
    each layer with bounded retry.
  - Live actions: `EntityMoveAction` (deterministic gravity + drag + Euler
    position integration) and `EntityCollisionResponseAction` (equal-mass
    elastic velocity exchange, momentum-conserving).
- **RW-consistency upheld.** The live MOVE writes velocity (for gravity), so
  `moveRw` was updated to declare that write — keeping the RW-set honest about
  what the action touches. A test pins this contract.
- **Determinism proven end to end.** `EntityReplayDeterminismTest` drives 8
  gravity-affected, colliding entities through the executor, hashes physics
  state per tick via the real replay harness, runs twice, and verifies
  bit-for-bit identical hash sequences (150 ticks fast; 5,000 ticks
  `slow`-tagged, ~30s). Plus unit tests asserting faithful physics (free-fall
  reproducibility, momentum conservation). Full suite green.
- **Same honest scope caveat as redstone:** this is self-consistency, not
  zero-diff against vanilla physics (Nebula's gravity/drag constants are a
  simplified model, not byte-matched to Minecraft). It proves the
  deterministic-execution machinery works for the entity subsystem; matching
  vanilla numerics is a later, separate step.
- **Next:** terrain-aware MOVE (the RW-set already reads neighbouring blocks),
  AI_GOAL with the seeded `RandomUsage` path (tests layered RNG determinism),
  and a combined redstone+entity tick once a shared world-state facade exists.

### 2026-06-09 (layered RNG determinism — a DG2 gate)

- **Why this next.** Reproducible randomness is an explicit DG2 acceptance
  criterion (arch doc §11: "random over-budget re-execution rate <1%") and the
  hardest determinism property — RNG outcomes must not depend on execution
  order. The primitives existed (`DeterministicRandom` with call counting,
  `RandomBudget`) but nothing derived per-task seeds or proved order-independence.
- **`LayeredRandomSource` (nebula-core).** Derives a per-task
  `DeterministicRandom` from the logical coordinate
  `(worldSeed, tick, entityId, instance)` using a SplitMix64 finalizer for
  strong avalanche. A task's stream depends only on its coordinate, never on
  thread or layer order — so parallel entity AI can replay deterministically.
  Unit tests cover same-coordinate reproducibility, per-dimension seed
  variation, and collision-free derivation over a dense 2500-coordinate grid.
- **Wired into the entity path.** `EntityTaskRunner` optionally takes a
  `LayeredRandomSource` and, per task, seeds a `DeterministicRandom` from the
  current tick + parsed entity ID; `EntityTaskContext.random()` exposes it and
  **throws if an action consumes RNG without a source** (catching undeclared
  `RandomUsage` instead of silently diverging). Added a live
  `EntityGoalSelectAction` (AI_GOAL) that consumes bounded RNG within its
  declared budget and respects its RW-set (reads `ai_state`, writes
  `goal_target`).
- **Order-independence proven two ways.** `EntityRandomDeterminismTest` shows
  (a) shuffled task input yields identical goal assignments, and (b) — the
  stronger claim — driving the runner **directly in forward vs. reverse order**
  (bypassing the DAG's ID-sort) still produces byte-identical results. This
  confirms order-independence is a property of the seeding itself, which is the
  precondition for safe parallel AI execution. Full suite green.
- **Next:** integrate `RandomBudget` allocate/evaluate into the tick loop
  (measure the DG2 over-budget rate on a real AI population), then terrain-aware
  MOVE and a combined redstone+entity tick.

### 2026-06-09 (DG2 random-budget metric wired into the tick loop)

- **The DG2 over-budget metric is now live, not just a primitive.**
  `RandomBudget` existed (allocate/evaluate/downgrade) but nothing fed it from
  real execution. `EntityTaskRunner` now optionally takes a `RandomBudget`:
  per RNG-declaring task it allocates a budget from the task's declared
  `RandomUsage` estimate, then after execution evaluates actual
  `DeterministicRandom.callsMade()` against it. `beginTick` resets per-tick
  stats; `currentOverBudgetRate()` exposes the DG2 metric (arch doc §11.2
  target: over-budget re-execution rate &lt;1%).
- **Only RNG-declaring tasks count.** The estimate is pulled from the task's
  RW-set `RandomUsage` (skipping `NONE`), so non-RNG tasks don't dilute the
  denominator — the rate reflects the population that actually rolls dice.
- **Metric correctness proven.** `EntityRandomBudgetTest` drives 100-entity
  populations through the tick loop: a well-behaved population reports 0%
  (comfortably under DG2's 1%); a controlled over-consuming minority (5 of 100)
  reports exactly 5%; consuming exactly the allocated budget counts as commit,
  not over-budget. Full suite green.
- **Next:** terrain-aware MOVE (RW-set already reads neighbour blocks); a
  combined redstone+entity tick once a shared world-state facade exists; and,
  externally blocked, wiring any of the self-consistency harnesses to a real
  Folia capture for true zero-diff DG1/DG2.

### 2026-06-09 (NEBULA-PATCH-2026-001 §变更四 — T2/T3 fidelity tiers)

- **Applied the external review patch's fidelity-tier spec** (docs/星云布丁002.md).
  The patch defines four tiers (T0 strict → T1 statistical → T2 relaxed → T3
  max-parallelism) and a downgrade path `T0→T1→T2→T3→fallback`. The code had
  only T0/T1/T2 and jumped T2→fallback.
  - `FidelityTier`: added `T3` (maximum parallelism, no determinism guarantee)
    with full per-tier semantics documented from the patch.
  - `FidelityDowngradeController`: implemented `T2→T3` (MSPT >50ms for 60s =
    1200 ticks) per the patch's updated §16.2 table; `T3` is terminal under
    metric pressure (only an unrecoverable DAG error forces fallback). Added
    tests for the new transition, the MSPT-streak reset at T2, T3 terminality,
    and the per-tier `requiresBudget`/`strictRandom` contract.
- **Closed the loop to the live DG2 metric.** `EntityFidelityDowngradeIntegrationTest`
  feeds the runner's measured `currentOverBudgetRate()` into the controller and
  proves the end-to-end T0→T1 trigger. This surfaced an important, non-obvious
  property of `RandomBudget`: it is **adaptive** (budget = max(1.5×historical,
  min)), so *steady* high RNG usage is absorbed and does NOT downgrade — only
  consumption that keeps *outrunning* the adaptive budget (a sustained spike)
  trips the trigger. Both behaviours are now pinned by tests.
- **Patch items still open** (tracked for later): 变更一 (Phase 1.5 annotation
  maintenance toolchain), 变更三 (VAP plugin certification),
  变更五 (lock target MC 1.21.4), 变更六/七 (perf model + competitor docs).
- **Next:** terrain-aware MOVE; combined redstone+entity tick; and the
  externally-blocked Folia reference capture.

### 2026-06-09 (NEBULA-PATCH-2026-001 §变更二 — DAG build-time budget + degrade)

- **Applied the patch's avalanche-prevention spec** (§4.3.1, §16.1). The patch
  mandates a hard build-time budget so a single slow build (redstone computer,
  dense farm) can't snowball into consecutive per-tick timeouts. Three
  deterministically-testable pieces:
  - `CoarseDagBuilder` — the legal-but-suboptimal fallback the degrade path
    falls back *to*: a serial chain in `DeterministicOrdering` order. Always
    acyclic (a chain has no cycles), so a topological sort always exists;
    correctness preserved (all pairs ordered), only parallelism lost. Matches
    the patch's "粗粒度串行块".
  - `DagBuildBudget` — budget thresholds (2ms total, 1.5ms per-bucket coarsen,
    0.5ms merge-opt skip), degrade recording, the 10-consecutive-tick admin
    warning, and the §4.3.1 diagnostics: `build_time_p50/p99/max` over a
    100-tick ring buffer plus `degraded_ticks_ratio`.
  - `BudgetedDagBuilder` — facade that times the optimal `BucketDagBuilder` and,
    once degradation is sustained (warning active), proactively switches to the
    coarse builder until builds recover. Both paths yield a legal DAG.
- **Deliberate scope call.** The patch also describes interrupting an in-flight
  *parallel* build at 1.5ms and coarsening only the unfinished buckets. The
  current `BucketDagBuilder` joins its fork-join tasks and can't be pre-empted
  without risking nondeterminism (and the blocker doc warns against
  destabilising it). So I implemented the coarser, deterministic guard
  (detect sustained overrun → switch the whole build to the serial fallback)
  and documented the per-bucket interruption as deferred pending an
  interruptible parallel build.
- **A real finding from a test failure.** The first `CoarseDagBuilder` tests
  assumed lexicographic task ordering; they failed because the project's
  `DeterministicOrdering.compareTaskIds` orders by SHA-256 hash, not
  alphabetically. The production code was right — I rewrote the tests to assert
  order-agnostic structural invariants (N-1 chain edges, single head/tail,
  one task per topological layer) instead of a hard-coded sequence. More robust
  and correct.
- **Next:** terrain-aware MOVE; combined redstone+entity tick; and the
  externally-blocked Folia reference capture.

### 2026-06-09 (remaining patch items — 变更一/三/五/六/七)

Closed out the rest of NEBULA-PATCH-2026-001 with the code-tractable parts of
each item:

- **变更五 (lock target version).** `TargetVersion` (nebula-core) centralises
  the MC 1.21.4 / Folia 26.1.x anchor as a single constant with an
  `isCurrent(verifiedAt)` check, instead of scattered string literals.
- **变更三 (VAP plugin certification).** New `org.nebula.core.vap.cert` package:
  `CertificationLevel` (Nebula Ready/Optimized/Native → green/silver/gold,
  mapped onto the existing `VapLevel` LEVEL_0/1/2), `PluginCertification`
  (catalog entry with yearly staleness per §13.7.2), and
  `PluginCertificationCatalog` (case-insensitive searchable catalog backing
  `/nebula plugins`, with level filters and stale-entry reporting).
- **变更一 (Phase 1.5 annotation maintenance).** New `org.nebula.maintenance`
  package: `ChangeLevel` (the MSD's Level 0/1/2 classification with its
  auto-migrate / auto-draft / manual-review policy) and
  `AnnotationCoverageDashboard` (per-subsystem coverage, Level-2 annotation
  debt, and the patch's &lt;5%/year decay target via `meetsDecayTarget`). The
  ASM bytecode-diff engine itself is out of scope here (needs decompiled MC
  sources), but the classification and health-tracking model is in place and
  tested.
- **变更六/七 (perf model + competitor analysis).** Documentation-only items,
  recorded in `docs/patch-002-perf-and-competitor.md` (revised 60–70%-efficiency
  speedup table with historical references; Folia/Luminol/Nebula comparison and
  the causal-preservation-vs-spatial-partition positioning), each cross-linked
  to the code that backs its claims.
- **Patch status:** 变更二/四 implemented earlier this session; 变更一/三/五/六/七
  now done to the extent the environment allows. All seven patch items are
  tracked in-repo. Full suite green.
- **Next:** terrain-aware MOVE; combined redstone+entity tick; and the
  externally-blocked Folia reference capture / benchmark.

### 2026-06-10 (变更一 upgrade — real MSD signature engine on decompiled 1.21.4)

- **Decompiled MC 1.21.4 Mojmaps sources became available** (`decompiled MC/`,
  gitignored — 6.9k `.java` files, WORLD_VERSION 4788). This unblocked the
  signature-engine part of 变更一 I had deferred for lack of source input.
- **Built the actual §14.3.5 组件 A engine** (`org.nebula.maintenance`):
  - `MethodSignature` — owner + name + param-type identity, with a name+arity
    key for rename/reorder detection.
  - `JavaSignatureExtractor` — parses method declarations from decompiled Java:
    flattens multi-line signatures, keeps generics with nested commas
    (`StateDefinition.Builder<Block, BlockState>`), strips parameter annotations
    (`@Nullable`, `@Block.UpdateFlags`) and `final`, normalises varargs, and
    excludes constructors/fields/control-flow.
  - `SignatureDiffer` — classifies old↔new method diffs into the existing
    `ChangeLevel` (Level 0 unchanged / Level 1 param-or-rename / Level 2
    return-type, modifier, or disappeared), with per-level summary counts and
    the patch's auto-migration-rate metric.
- **Validated against real Mojmaps output**, not just synthetic input: a
  copied `RepeaterBlock.java` fixture lives in test resources, and an ad-hoc
  cross-check ran the extractor over RedStoneWireBlock (35 methods),
  ComparatorBlock, RedstoneTorchBlock, and DefaultRedstoneWireEvaluator —
  correctly extracting 8-parameter signatures (`updateShape`) and
  `MapCodec<? extends RedstoneTorchBlock>` generics.
- **Honest remaining scope:** this is a source-signature diff. The patch also
  describes a bytecode data-flow summary for finer Level 2 (semantic) detection
  and the CI regression runner (组件 B) + dashboard wiring (组件 C is modelled,
  not yet fed from a real annotation scan). Those remain future work; the
  signature classifier they build on is now real and tested.

### 2026-06-10 (变更一 achieved — 组件 B regression runner + 组件 C real feed)

- **组件 B — annotation regression runner.** `AnnotationRegressionRunner`
  cross-references the declared annotation library against the methods actually
  present in the decompiled source, classifying each reference PRESENT / MISSING
  / AMBIGUOUS. This is the CI gate the patch requires so annotations cannot
  "silently fail" when upstream code changes. `JavaSignatureExtractor` gained
  `extractTree(Path)` (scan a whole source tree) and `superclassOf` (parse the
  `extends` clause).
- **Inheritance-aware resolution — driven by a real finding.** The first
  real-source run flagged `RepeaterBlock.tick`, `RepeaterBlock.neighborChanged`,
  and `ComparatorBlock.neighborChanged` as MISSING. That was correct: those
  methods are declared on the parent `DiodeBlock` and inherited — the annotation
  library legitimately references them by leaf class. So the runner now takes an
  optional simple-name→superclass map and resolves a reference if the method
  exists on the class **or any ancestor** (cycle-guarded). The class hierarchy
  is extracted from the sources' `extends` clauses.
- **组件 C — fed from a real scan.** `RedstoneAnnotationMaintenanceTest` pulls
  every method reference declared across `RedstoneAnnotations.componentTemplates()`,
  runs the inheritance-aware regression check against bundled decompiled MC
  1.21.4 fixtures (RepeaterBlock, DiodeBlock, RedStoneWireBlock,
  RedstoneTorchBlock, ComparatorBlock — 68K of real source), and builds the
  `AnnotationCoverageDashboard` from the result. The bundled-subsystem
  annotations all resolve to real methods (incl. inherited), proving the asset
  is anchored to MC 1.21.4.
- **变更一 status: achieved** to the extent the environment supports — 组件 A
  (signature extractor + differ), 组件 B (regression runner), and 组件 C
  (coverage dashboard fed from a real scan) are all implemented and validated
  against real sources. Remaining future work is the bytecode data-flow summary
  for finer Level 2 semantic detection (needs compiled classes, not just
  source) and wiring the runner into an actual CI pipeline step.

### 2026-06-10 (terrain-aware entity MOVE — collision physics)

- **Closed a declared-vs-actual gap.** The MOVE RW-set declared reads of the
  entity's cell and its 6 neighbour blocks, but `EntityMoveAction` ignored them
  — pure free-fall. Added a read-only terrain layer so MOVE actually uses those
  declared block reads, restoring RW-consistency (the invariant the integrity
  checker enforces).
- **`TerrainView`** — an immutable, version-free solid-block oracle (terrain
  doesn't mutate during entity physics). Factories: `EMPTY` (open void),
  `flatFloor(y)`, and `ofSolids(set)`. Threaded through `EntityTaskContext`
  (`terrain()`) and configured on the runner via `withTerrain(...)`.
- **`EntityMoveAction` now resolves terrain collision:** after gravity +
  integration, if descending into a solid block it clamps the entity to the
  block top and zeroes vertical velocity. `EntityReplayDeterminismTest` etc.
  use the default `EMPTY` view, so existing free-fall determinism is unchanged.
- **Tests:** `TerrainCollisionTest` — falls in void, rests on a flat floor,
  no jitter once landed, per-column solidity (block vs air), and deterministic
  landing height across runs. Full suite green.
- **Honest limitation:** collision reads the cell below the *new* position
  (`floor(x), floor(newPos.y), floor(z)`). At normal fall speeds this stays
  within the declared neighbour cells, but a very fast fall (>1 block/tick)
  could read a cell the static RW-set didn't declare — a sub-stepping or
  swept-collision pass is the proper fix and is future work. Documented rather
  than silently assumed correct.

### 2026-06-10 (combined redstone+entity tick — cross-subsystem integration)

- **The integration milestone.** Both subsystems were live and deterministic
  individually, but nothing ran them together. `CompositeTaskRunner`
  (nebula-core) routes tasks to subsystem runners by type, so redstone wire
  propagation and entity physics build into ONE DAG and execute together — the
  first concrete demonstration of the core architectural claim that
  causally-independent tasks share one dependency graph regardless of subsystem.
- **`LayerCommitting`** interface extracted in core (shared `run` +
  `commitLayer` + `resetLayer` lifecycle); both `RedstoneTaskRunner` and
  `EntityTaskRunner` now implement it, and the composite fans commit/reset
  across all sub-runners so a combined layer commits atomically.
- **New `nebula-integration` test module** (test-only, depends on both
  subsystems — avoids a module cycle). `CombinedTickTest` proves: both
  subsystems advance in one tick (wire → 14 via redstone runner, entity falls
  via entity runner), the combined run replays deterministically (hash of both
  worlds identical across two runs), and an unrouted task type fails loudly
  rather than being silently dropped.
- **Routing handles SCC compounds:** a contracted task routes by its first
  member's type (SCC members are mutually conflicting → same subsystem).
- Full suite green across all 9 modules (34 gradle tasks).
- **Next:** swept/sub-stepped collision (the fast-fall limitation); the AI
  pipeline (SENSE→GOAL→PATHFIND→ACT on the layered-RNG foundation); and the
  externally-blocked Folia reference capture to convert self-consistency into
  true zero-diff DG1/DG2.

### 2026-06-10 (swept collision + AI pipeline + block-entity subsystem)

Three features built against the decompiled MC 1.21.4 Mojmaps sources.

- **Swept collision (closes the prior fast-fall limitation).** `EntityMoveAction`
  now sweeps the descent one block-cell at a time and lands on the top of the
  FIRST solid block in the column, instead of checking only the destination
  cell. A >1 block/tick fall can no longer tunnel through a thin floor, and
  every probed cell is one the MOVE RW-set declares. `TerrainCollisionTest`
  adds fast-fall-no-tunnel and land-on-first-of-stacked-blocks cases.
- **AI pipeline live (§7.2).** `AiPipelineActions` implements SENSE → GOAL_SELECT
  → PATHFIND → ACT as executable actions on the entity state layer, each
  touching exactly the `ai_state.*` / position / health fields its
  `AITaskFactory` RW-set declares. The four stages form a RAW dependency chain
  (4 serial layers per entity); distinct entities' pipelines parallelise.
  GOAL_SELECT and ACT consume layered RNG within their declared budgets.
  `AiPipelineTest` proves the chain shape, full-stage execution, and
  deterministic order-independent results across a population.
- **Block-entity subsystem live (§3.3).** New versioned `BlockEntityState` +
  snapshot + context + `BlockEntityTaskRunner` (a `LayerCommitting` runner, so
  it composes into the combined tick). `BlockEntityActions` implements hopper
  and furnace faithful to the decompiled constants: hopper moves one item then
  arms an 8-tick cooldown (`MOVE_ITEM_SPEED`), furnace smelts one item after 200
  accumulated cook-ticks (`BURN_TIME_STANDARD`) while consuming fuel.
  `BlockEntitySubsystemTest` covers transfer+cooldown, smelt-at-200, no-fuel
  no-op, and deterministic multi-furnace simulation.
- Full suite green across all 9 modules.
- **Next:** wire the block-entity runner into the combined tick (3-way:
  redstone + entity + block-entity); fluids/explosions live paths; and the
  externally-blocked Folia reference capture.
