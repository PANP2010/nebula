# Authority Transition Research

**Document**: docs/authority-transition.md
**Author**: Nebula Research
**Date**: 2026-07-12
**Status**: RESEARCH — not implementation

---

## 1. Problem Statement

Phase 0 (current prototype) is an **observe-only shadow**: the DAG runs after Folia's authoritative tick, computes state into a private CAS store, and Folia's next tick overwrites it with the same result. The shadow never replaces Folia's behavior — it only replicates it. This is sufficient to prove correctness (DG1: "multi-thread Nebula == single-thread MC"), but not sufficient to deliver the architecture's stated goal: **MSPT reduction relative to Folia**.

To achieve the architecture's MSPT reduction claim, Nebula must eventually become **authoritative**: the DAG executes in place of Folia's serial tick, and its results are the authoritative state Folia reads on the next tick.

This document researches the paths to that transition.

---

## 2. Why Phase 0 Cannot Become Authoritative Automatically

The current code path:

```
Folia tick → Nebula DAG → CAS write-back → Folia tick N+1
                   ↑
           reads Folia's N state
           as its input
```

Every CAS write-back is immediately overwritten by Folia's next authoritative tick. The DAG computes correctly but its results are discarded.

To become authoritative, Nebula must either:
1. Run **before** Folia's tick (Folia reads Nebula's output as input), or
2. Run **in place of** Folia's tick (Folia delegates tick authority to Nebula)

Both require understanding Folia's tick architecture.

---

## 3. Folia Tick Architecture

Based on source analysis of Folia 26.1.2 (`io.papermc.paper.threadedregions`):

### 3.1 The Tick Hierarchy

```
RegionizedWorldServer.tick()
  ├── GlobalRegionScheduler.tick()
  │     ├── Per-region scheduler ticks (RegionTaskScheduler)
  │     │     ├── Entity tick
  │     │     ├── Block entity tick
  │     │     └── Block tick (redstone, etc.)
  │     └── Global tick (weather, game rules, etc.)
  └── World border / command block execution
```

### 3.2 The Region Tick Scheduling Model

Folia divides the world into 32×32×32 block buckets (regions). Each region has its own scheduler thread. The region scheduler runs tasks in two phases:

1. **Scheduler phase**: Run all pending scheduled tasks (entity ticks, block ticks, etc.)
2. **Tick end**: Post-tick processing (chunk unload, entity despawn, etc.)

The key classes:
- `RegionizedWorldServer` — entry point, manages the region registry
- `RegionTaskScheduler` — per-region task queue and executor
- `RegionScheduler` — Bukkit API facade for `runOnRegion()`

### 3.3 Where Redstone Ticks Fit

In vanilla Paper, `BlockRedstoneDirector` fires `BlockRedstoneEvent` during `Block.tick()`. In Folia, this fires on the region thread that owns the block's chunk. Nebula intercepts this via:
1. `NeighborUpdateTransformer` (ASM bytecode rewrite)
2. `GlobalRegionScheduler` lifecycle driver (beginTick/endTick hooks)

This interception runs **within** Folia's tick, not before or after it.

---

## 4. Path A: Nebula Fork — Nebula Executes First, Folia Reads Results

### 4.1 Concept

```
[HOOK] → Nebula DAG executes → NMS state updated →
  Folia tick begins → Folia reads Nebula's state → Folia tick completes
```

The hook replaces or precedes Folia's per-region tick execution. Nebula's DAG produces the authoritative state before Folia's game logic runs.

### 4.2 Feasibility Assessment

**Key challenge**: The hook must intercept at a point where:
1. Folia's internal state (entity positions, block states) is still readable
2. Folia's game logic has not yet written to that state
3. After the DAG runs, Folia reads Nebula's output as its input

This is equivalent to "insert Nebula's DAG execution at the very start of each region's tick". The practical question is: **can Folia be configured to run a callback before its tick?**

**Folia's existing hooks**:
- `GlobalRegionScheduler.runAtFixedRate()` — schedules tasks ON the global tick thread, BETWEEN tick phases. This is where Nebula's current lifecycle driver runs.
- `RegionScheduler.execute()` — runs tasks on a specific region's thread. Used by Nebula to dispatch per-entity/block-entity DAG tasks.
- Bukkit event listeners — fire DURING tick processing, not before.

**The timing problem**: The existing `runAtFixedRate` hook fires between Folia's tick phases, but Folia has already READ the entity/block state it will use for this tick. Nebula's DAG would compute from stale state.

**Where a true "before tick" hook might live**: `RegionTaskScheduler.tick()` is the region's per-tick entry point. A hook there could run the DAG before anything else. But:
- This requires bytecode injection into Folia's region scheduler class
- Folia is actively developed — injected offsets would break on version updates
- The team has explicitly avoided deep Folia injection (see `RegionizedServer` class dependency comment in `FoliaRuntimeDetector`)

**Honest verdict**: Path A (Nebula Fork) is **technically the right architecture** but **not currently feasible** without:
1. A stable bytecode injection mechanism for Folia's region tick entry points, OR
2. Folia explicitly exposing a "pre-tick" hook API, OR
3. Becoming a hard fork of Folia (abandoning the plugin model)

### 4.3 Next Steps for Path A

- [ ] **Open a Folia feature request** asking for a `PreRegionTickEvent` or `NebulaTickEvent` that fires before region game logic
- [ ] **Prototype bytecode injection** into `RegionTaskScheduler.tick()` using the existing ASM infrastructure — measure tick-time overhead of the hook
- [ ] **Evaluate hard-fork feasibility**: how much of Folia needs to be replaced? (The answer determines whether this is a fork or a rewrite)

---

## 5. Path B: Incremental Authority Transfer

### 5.1 Concept

Instead of replacing Folia's entire tick, Nebula progressively takes over subsystems one at a time. Each subsystem has its own "authority gate" — when the DAG's results match Folia's for N consecutive ticks, the gate opens and Nebula becomes authoritative for that subsystem.

```
Tick N:
  Folia tick → Nebula DAG (all subsystems)
  ↓
  Compare: for each subsystem, does Nebula's result match Folia's?
  ↓
  If all match for K consecutive ticks → open authority gate for that subsystem
  ↓
  From Tick N+1: Nebula is authoritative for that subsystem, Folia skips it
```

### 5.2 Subsystem Authority Gates

| Subsystem | Gate Criterion | Notes |
|---|---|---|
| Redstone | `matched == total` for 1000 consecutive ticks | Phase 0 already proven for single-thread |
| Entity MOVE (vertical) | `EntityDivergenceTracker.drift < 0.01` for 500 ticks | Write-back gateway |
| Block entity (hopper) | `BE-SETTLED` match for 500 ticks | |
| Fluid | Settled-state match for 500 ticks | |
| Entity COLLISION | Collision frequency match for 1000 ticks | |
| Entity AI | Behavior distribution match for 1000 ticks | Hardest to quantify |

### 5.3 Folia Read Path Interception

The key technical challenge: when Nebula becomes authoritative for a subsystem, Folia must read Nebula's state instead of computing its own.

**Mechanism**: The NMS bridges (`NmsBlockStateBridge`, `NmsEntityStateBridge`, etc.) already provide read/write access to Folia's NMS state. If Nebula's DAG writes to Folia state (not CAS), Folia naturally reads Nebula's output — because Folia reads from the same NMS state.

**What changes**: Instead of writing to CAS, the DAG writes directly to NMS. Folia reads those NMS values as its input for the next tick.

**Safety**: The authority gate ensures the first N ticks are identical, so the cutover is transparent from the client's perspective.

### 5.4 Cross-Subsystem Dependencies

When subsystem A becomes authoritative and subsystem B is still Folia-authoritative, the dependency edge goes: Folia → Nebula. Folia reads Nebula's output from tick N, Nebula reads Folia's output for tick N+1.

This creates a **one-tick latency coupling**: Nebula's computation for tick N+1 is based on Folia's result from tick N, but Folia's computation for tick N+1 is based on Nebula's result from tick N.

For deterministic systems, this converges if both computations produce identical results. For nondeterministic systems (entity AI, Random), the authority gate must account for statistical rather than bit-exact matching.

### 5.5 Implementation Plan

1. **Phase 1**: Add authority gate configuration per subsystem (`nebula.yml`)
2. **Phase 2**: Modify `executeOwnedDag` to write to NMS (not CAS) when gate is open
3. **Phase 3**: Add Folia-side "skip" when Nebula-authoritative: for the relevant subsystem hooks, Folia delegates to Nebula's result
4. **Phase 4**: Validate with 1000-tick cutover tests

**Honest verdict**: Path B (Incremental Transfer) is **the most pragmatic near-term path**. It doesn't require Folia API changes, can be implemented incrementally, and has a natural rollback mechanism (close the gate if divergence appears).

---

## 6. Path C: Folia as Network Layer

### 6.1 Concept

Treat Folia as a **network synchronization layer** rather than a game logic server. Nebula becomes a standalone server that handles all game logic; Folia handles only:
- Player connections
- Chunk data serialization
- Entity metadata synchronization
- Scoreboard / chat / permissions

This is a **hard fork**, not a plugin.

### 6.2 Architecture

```
Players → Folia (network only) → Nebula (game logic) → Nebula tick output
         ↑                                              ↓
         └──────── client bound packets ───────────────────┘
```

Folia's game logic (entity tick, block tick, etc.) is replaced by Nebula's DAG. Folia's network stack remains unchanged.

### 6.3 Feasibility

- Requires forking Folia's server entry point
- Significant maintenance burden (keep fork in sync with upstream Folia)
- No existing precedent in the Minecraft server ecosystem
- **Honest verdict**: This is a **Phase 3+ aspiration**, not a near-term path

---

## 7. Critical Open Questions

### 7.1 Can Folia Run Without Its Own Tick?

When Nebula takes over a subsystem, Folia still needs to run for other subsystems. The question is whether Folia can tick one subsystem and skip another.

**Evidence**: Folia's region scheduler runs tasks from a queue. If Nebula's hook intercepts before the tick and writes results back, Folia's tick for that subsystem would be a no-op. But Folia's tick code would still execute the task scheduler overhead.

**Unknown**: Whether skipping a subsystem's tick causes Folia to malfunction (e.g., tick rate monitoring, statistics, watchdog).

### 7.2 What Happens to Random?

If Nebula becomes authoritative, its Random number generator must produce the same sequence Folia would have. This is the core of the Random subsystem (Chapter 11). The shadow execution + re-execution protocol is designed to handle this — but it assumes Nebula has already computed the tick, not that it's authoritative from the start.

**Key insight**: For Phase 0, Nebula's CAS contains Folia's Random-consumed state. The authority cutover must ensure the Random sequence is preserved. The `RandomInstance` tracking in the RW-set is exactly the mechanism to do this.

### 7.3 Chunk Load/Unload

When Nebula is authoritative, who decides when chunks load/unload? Folia's chunk management is tied to entity ticking and player movement. If Nebula controls entity physics, it also needs to control chunk lifecycle.

**Current architecture**: Chunk load/unload uses MVCC snapshot. The authority transition must include chunk lifecycle as a subsystem with its own authority gate.

---

## 8. Recommendation

**Immediate (next 30 days)**:
1. Implement Path B authority gates for the redstone subsystem (the simplest subsystem)
2. Validate: run with `-Dnebula.authority.redstone=true`, measure MSPT vs Folia baseline
3. Document the latency coupling finding (one-tick circular dependency)

**Short-term (next 90 days)**:
1. Add Path B gates for entity MOVE (vertical write-back gateway)
2. Measure the cross-subsystem latency coupling with real workloads
3. Open Folia feature request for `PreRegionTickEvent`

**Medium-term (Phase 1)**:
1. Path B gates for all Phase 1 subsystems
2. Quantify MSPT reduction with partial authority transfer
3. Evaluate whether Path A becomes feasible based on Folia's roadmap

**Long-term (Phase 2+)**:
1. Path A prototyping (bytecode injection feasibility study)
2. Path C evaluation (is hard-forking Folia worth it?)

---

## 9. Glossary

| Term | Definition |
|---|---|
| Authority gate | A configurable threshold that transitions a subsystem from Folia-authoritative to Nebula-authoritative based on consecutive tick-match metrics |
| Shadow execution | Phase 0 mode: Nebula computes a copy of Folia's state without replacing it |
| Authoritative execution | Mode where Nebula's DAG output becomes the state Folia reads on the next tick |
| CAS store | Nebula's private copy-on-write state store, used in Phase 0 observe-only mode |
| NMS bridge | Component that reads/writes Folia's native Minecraft state from/to Nebula's CAS store |
| Authority gate criterion | The measurable condition (e.g., `matched == total` for 1000 ticks) that must be met before a subsystem transitions to authoritative |

---

## 10. References

- Architecture doc: `docs/nebula-architecture.md` (Chapter 14: Phase timeline, Chapter 11: Random subsystem)
- Project status: `docs/PROJECT_STATUS.md`
- DG2 Criterion 3: "MSPT relative to Folia ≤ 70%" (requires authority transition)
- Folia source: `io.papermc.paper.threadedregions` package
