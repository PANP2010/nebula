# Nebula Project Status Report

**Date**: 2026-07-08 (updated 19:00 — DAG execution AND zero-diff capture VERIFIED on real Folia)
**Branch**: feat/fix-folia-scheduler-v2  
**Completion**: ~50% (two milestones reached: end-to-end DAG execution + deterministic zero-diff capture verified; per-tick MSPT measurement now exists and is Folia-verified, but load testing and a baseline-vs-Nebula comparison remain)

---

## Executive Summary

Nebula has a solid architectural foundation with 17,305 lines of production code, 678 passing unit tests, and complete build toolchain integration.

**MILESTONE 1 (2026-07-08): The core DAG execution path now runs on a real Folia 26.1.2 server.** A live lever→wire→lamp circuit was toggled via RCON and produced repeatable, exception-free DAG ticks:

```
[18:17:48] [org.nebula.plugin.NebulaPlugin] DAG tick: 3 tasks, 1 microsteps in 12ms
```

Toggling the lever ON produced 30 DAG ticks; toggling OFF produced 15 more. Zero commit failures, zero DAG exceptions, microsteps well within the ≤256 bound. This closes the central integration gap (B1/B2/B3) that had blocked the project since inception.

**MILESTONE 2 (2026-07-08): Deterministic zero-diff capture now works end-to-end.** After fixing the capture path (see B6 below), two identical 40-tick captures on the live server produced **byte-for-byte identical `.nrp` files** — empirically demonstrating the deterministic-replay property the project was built to prove.

**What is still NOT verified**: performance under load. Per-tick MSPT measurement now exists (`TickTimeRecorder` + `/nebula perf`, commit be85033) and is Folia-verified on a small circuit (steady-state avg 1.24ms / p50 0.95ms / p95 2.86ms / p99 3.47ms over 100 ticks). What remains: load testing on large multi-region circuits and a baseline-Folia-vs-Nebula MSPT comparison against DG1's 30%-reduction target. The project is a working prototype with two proven properties and a working perf-measurement surface, not a performance-validated or "playable" release.

This document provides an honest assessment of what works, what doesn't, and the path forward.

---

## The Fix That Unblocked It (2026-07-08)

Two concrete issues were preventing the first DAG tick:

1. **World-name key mismatch (B3, real bug).** The agent interceptor recorded
   dirty positions under the NMS namespaced key `nebula-global::minecraft:overworld`
   (from `Level.dimension().location()`), but the global-tick lifecycle driver
   drained with the Bukkit folder name `nebula-global::world` (from
   `World.getName()`). The two never matched, so every recorded update was
   silently dropped. Fixed by normalizing `RedstoneTickHook.key()` through
   `DimensionIds.fromName()` so both name forms converge on the same
   dimension-keyed bucket — consistent with the rest of the pipeline, which is
   dimension-keyed everywhere else. Regression tests added.

2. **Empty componentMap for command-placed redstone (observability gap).** RCON
   `setblock`/`fill` do not fire `BlockPlaceEvent`, so redstone placed via
   commands was never registered and the DAG resolver returned null for every
   position. Added `/nebula scan` (region-thread-safe rescan of loaded chunks)
   and surfaced the registered-component count in `/nebula status`.

**The other reason no DAG tick had ever appeared:** the decisive experiment was
never actually performed on a running server — a static circuit generates no
`BLOCK_UPDATE`s, so nothing ever exercised the pipeline. Once a real circuit was
built, force-loaded, scanned, and *toggled*, the chain fired immediately.

---

## What Actually Works

### ✅ Architecture & Design
- 1,546-line whitepaper with complete formal definitions
- Well-structured 7-module Gradle project
- Clean separation: core → redstone/entity → folia-bridge → folia-adapter → plugin
- Comprehensive architecture diagrams and documentation

### ✅ Build & Toolchain
- Multi-module Gradle 8.13 build
- Shadow jar packaging (513KB deployable plugin)
- Java agent build and packaging
- Java 21 (compile) + Java 25 (runtime for NMS adapter)
- All 678 unit tests pass

### ✅ Core Components (Unit-Tested)
- **CAS state stores**: RedstoneWorldState, EntityPhysicsState, BlockEntityState
- **DAG primitives**: TaskNode, dependency tracking, topological execution
- **MicroStepScheduler**: Microstep expansion with change propagation
- **EntityTickExecutor**: Entity physics task generation
- **CompositeTaskRunner**: Multi-subsystem DAG routing
- **NMS bridges**: Block, Entity, BlockEntity state sync interfaces
- **Capture framework**: WorldStateHasher, ReplayRecorder

### ✅ Plugin Infrastructure
- Successfully loads on Folia 26.1.2
- `/nebula` command registration
- Folia runtime detection
- Agent retransform mechanism
- Chunk load/unload listeners
- Block place/break event handlers

---

## Blocker Status After 2026-07-08 Verification

| Blocker | Status | Evidence |
|---------|--------|----------|
| B1: RedstoneTickHook lifecycle | ✅ **VERIFIED WORKING** | DAG ticks fire every redstone change; lifecycle driver drives begin/end |
| B2: componentMap empty | ✅ **RESOLVED** | Async scan + new `/nebula scan` register components (16 found for test circuit) |
| B3: Agent interception chain | ✅ **FIXED** (key mismatch) + ⚠️ fallback active | `BlockRedstoneEvent` listener confirmed firing; agent path now key-consistent |
| B4: NMS sync overhead | ⚠️ Partially verified | Per-tick MSPT now measured (`/nebula perf`); small-circuit steady-state p99 3.47ms. Load testing + baseline comparison still open |
| B5: Redstone test world | ✅ Circuit persists in flat world | lever→15 wire→lamp at y=-59 |
| B6: Capture harness E2E | ✅ **VERIFIED WORKING** | Two identical 40-tick captures produced byte-for-byte identical `.nrp` files (deterministic zero-diff holds) |

Detailed history of each blocker follows below (retained for the record).

---

## Blocker History (how each was diagnosed and resolved)

> These entries describe the state **before** the 2026-07-08 verification and how
> each was closed. They are kept for the record. For current status, see the
> table above.

### ✅ B1: RedstoneTickHook Lifecycle (RESOLVED)

**Original problem**: `RedstoneTickHook.beginTick()` and `endTick()` were never called, so the DAG execution pipeline remained dormant. No code invoked these lifecycle methods — the `FoliaRegionTickExecutor` was created and registered, but there was no bridge from Folia's tick loop to Nebula's hook system.

**Fix**: Added `GlobalRegionScheduler.runAtFixedRate` in `NebulaPlugin.onEnable()` to call `beginTick`/`endTick` in alternating ticks.

**Verified 2026-07-08**: DAG ticks fire on every redstone change; the lifecycle driver drives begin/end as designed. Confirmed by live logs (`DAG tick: 3 tasks, 1 microsteps in 12ms`).

---

### ✅ B2: ComponentMap Empty at Runtime (RESOLVED)

**Original problem**: `WorldRedstoneScanner` scanned chunks asynchronously with a 100-tick delay. If redstone ticks occurred before scanning completed, `componentMap` was empty and the DAG had no tasks to execute. Compounding this, RCON `setblock`/`fill` do not fire `BlockPlaceEvent`, so command-placed redstone was never registered.

**Fix**: Added a synchronous scan of loaded chunks in `onEnable()` before any scheduled tasks run, plus a new `/nebula scan` command (region-thread-safe rescan) and a registered-component count in `/nebula status`. Added a guard that logs a warning when `componentMap` is empty.

**Verified 2026-07-08**: Components register correctly (16 found for the test circuit via `/nebula scan`).

---

### ✅ B3: NeighborUpdateInterceptor Chain (FIXED — key mismatch)

**Original problem**: The agent's ASM bytecode injection into `CollectingNeighborUpdater` appeared successful in logs, but BLOCK_UPDATE interception to `RedstoneTickHook.recordUpdate()` had never been verified end-to-end.

**Root cause found 2026-07-08**: A world-name key mismatch — the interceptor recorded dirty positions under the NMS namespaced key `nebula-global::minecraft:overworld`, while the tick driver drained `nebula-global::world` (the Bukkit folder name). The two never matched, so every recorded update was silently dropped.

**Fix**: Normalized `RedstoneTickHook.key()` through `DimensionIds.fromName()` so both name forms converge on the same dimension-keyed bucket. Regression tests added.

**Verified 2026-07-08**: The `BlockRedstoneEvent` listener is confirmed firing as a stable Bukkit-API fallback, and the agent path is now key-consistent.

---

### ⚠️ B4: NMS Bridge Performance (partially verified)

**Problem**: `executeOwnedDag()` calls `syncFromNms()` and `syncToNms()` for every task position. For large task sets, this may create unacceptable overhead.

**Status**: Per-tick measurement infrastructure now exists — `TickTimeRecorder` (nebula-core/metrics) records every DAG tick's elapsed time and `/nebula perf` reports p50/p95/p99 (commit be85033). Folia-verified on a small circuit: steady-state avg 1.24ms / p50 0.95ms / p95 2.86ms / p99 3.47ms over 100 ticks, well under the 50ms/20-TPS budget (a ~57ms first-tick JIT-warmup outlier correctly ages out of the recent-sample window). **Still open**: load testing on large multi-region circuits and a baseline-Folia-vs-Nebula MSPT comparison against DG1's 30%-reduction criterion. This is the next milestone.

---

### ✅ B6: Zero-Diff Capture End-to-End (VERIFIED 2026-07-08)

**Original problem**: `FoliaCaptureHarness` was blocked by B1, and once that was fixed, two further bugs meant capture still produced no meaningful output: (1) the hasher's `trackedPositions` set was never populated, so hashes carried no redstone state; (2) `WorldStateHasher.hashState()` read live NMS blocks from the global tick thread, which throws on Folia (region-thread-only reads).

**Fix**: Added `RedstoneCasStateHasher` (reads power levels from the thread-safe `RedstoneWorldState` CAS store instead of live NMS), wired `trackPosition`/`untrackPosition` into component register/unregister, and made `/nebula capture stop` save a timestamped `.nrp` file under `plugins/Nebula/captures/` with a distinct-hash summary.

**Verified 2026-07-08**: `/nebula capture start 40` recorded frames with zero exceptions (previously an NPE every tick). Two identical static 40-tick captures produced **byte-for-byte identical `.nrp` files** — the deterministic zero-diff property holds on a real server. `DAG tick: 16 tasks, 14 microsteps` observed for the full circuit under capture.

---

## Documentation vs. Reality Gap (historical — pre-2026-07-08)

> This section records the drift that existed **before** the milestone, for the
> record. The "Reality" column reflects the pre-verification state. See the
> current status tables above and in the README for present-day claims.

### README.md Claims vs. Actual Status (as of pre-verification)

| Claim | Reality at the time |
|-------|---------------------|
| "First playable release v0.1.0" | DAG had never executed on a real server |
| "Three-phase tick execution" | Code existed but never ran end-to-end |
| "Redstone DAG: MicroStepScheduler" | Worked in unit tests, not integrated |
| "NMS bridges: CAS ↔ real Bukkit state" | Interface existed, sync path untested |
| "Zero-diff capture framework" | Framework existed, never captured anything |
| "In-game commands" | Commands existed, `/capture start` untested |

**Update (2026-07-08)**: The first four rows are now resolved — DAG execution is verified on real Folia, and zero-diff capture (B6) is verified (byte-identical replay files). NMS performance (B4) is now partially verified: per-tick MSPT is measured and Folia-checked on a small circuit, but load testing and the baseline comparison remain — do not treat B4 as fully done yet.

### CHANGELOG.md Claims vs. Reality (as of pre-verification)

| Feature | Status at the time |
|---------|--------------------|
| Three-phase tick execution | Now verified running (2026-07-08) |
| MicroStepScheduler with microstep expansion | Now exercised by real redstone (2026-07-08) |
| EntityTickExecutor MOVE/COLLISION | Created; not yet wired into the live tick path |
| CompositeTaskRunner unified DAG | Wired; redstone path verified, entity path not yet |
| NMS bridges | Interfaces work; per-tick sync cost now measured (B4), load testing open |
| Zero-diff capture framework | Still never run end-to-end (B6) |
| In-game commands | `/status`, `/scan` work; `/capture` still untested |

---

## Test Coverage Analysis

### What's Tested
- ✅ 678 unit tests, all passing
- ✅ CAS state store operations
- ✅ DAG topological sort
- ✅ MicroStepScheduler logic
- ✅ Task factory generation
- ✅ State hasher correctness
- ✅ End-to-end DAG execution on real Folia (manual verification 2026-07-08)
- ✅ Agent + event-listener path delivering real updates to RedstoneTickHook

### What's NOT Tested
- ❌ Zero-diff capture recording real frames end-to-end (B6)
- ⚠️ NMS bridge performance under real *load* / MSPT comparison (B4 — per-tick measured, load testing open)
- ❌ Multi-region coordination at scale
- ❌ Entity subsystem on a live server
- ❌ Automated (non-manual) integration regression for the E2E path

**Note**: Unit coverage is high and the core E2E path is verified on real Folia, as is deterministic zero-diff capture. Per-tick performance is measured but only on a small circuit; performance *under load* remains unverified — do not read the single small-circuit measurement as proof of scale.

---

## Acceptance Criteria Status

### DG1: Redstone Subsystem (Target: Month 2)

| Criterion | Target | Current Status |
|-----------|--------|----------------|
| Zero-diff for 10k ticks | Pass | ⏳ DAG now executes; 10k-tick E2E zero-diff not yet run (B6) |
| Microsteps ≤ 256 per tick | ≤256 | ⏳ Well within bound on tiny circuit; not yet stress-tested |
| MSPT reduction | ≥30% | ❌ Not measured (B4) |

**Verdict**: DG1 not yet accepted. The execution path is verified, but the correctness and performance criteria still require live measurement.

---

### DG2: Entity Subsystem (Target: Month 4)

| Criterion | Target | Current Status |
|-----------|--------|----------------|
| Zero-diff for 50k ticks | Pass | ❌ Entity executor never used |
| Random budget violations | <1% | ✅ Unit tests pass |

**Verdict**: DG2 acceptance blocked by DG1.

---

### DG3: Full System (Target: Month 6)

| Criterion | Target | Current Status |
|-----------|--------|----------------|
| Full system zero-diff | Pass | ❌ Not started |
| VAP plugin compat | Level 0 ≥80% | ❌ Not tested |
| Load testing | 100 players @ 20 TPS | ❌ Not tested |

**Verdict**: DG3 acceptance blocked by DG1/DG2.

---

## Root Cause Analysis

### Why Did This Happen?

1. **Over-reliance on unit tests**: 659 passing tests created confidence without integration verification
2. **Documentation-driven development**: Docs were written assuming code would work, not reflecting reality
3. **No integration test harness**: Gap between unit tests and real server deployment
4. **Async complexity underestimated**: Folia's region threading model integration harder than expected
5. **Agent verification gap**: ASM injection success ≠ runtime behavior correctness

### Key Lesson

**Passing unit tests do not equal a working system.** A single end-to-end integration test showing "redstone placed → DAG executes → state changes" is worth more than 1000 unit tests for validating actual functionality.

---

## Path Forward

### Phase 1: Make It Work Once (Priority: P0)

**Goal**: Get DAG to execute successfully on real Folia server with real redstone.

**Tasks**:
1. ✅ Fix B1: RedstoneTickHook lifecycle (code committed, needs verification)
2. ✅ Fix B2: ComponentMap sync scan (code committed, needs verification)
3. ⏳ Verify B1/B2 fixes on test server
4. ⏳ Verify B3: Agent interception chain
5. ⏳ Add diagnostic logging throughout execution path
6. ⏳ **Success criterion**: See "DAG tick: N tasks, M microsteps in Xms" in logs

**ETA**: 2-3 days (assuming test server access)

---

### Phase 2: Make It Correct (Priority: P1)

**Goal**: Verify zero-diff property on simple redstone circuits.

**Tasks**:
1. Create minimal redstone test world (16-block wire, lever, lamp)
2. Run 1k-tick capture (smaller than 10k for faster iteration)
3. Verify state hashes match between runs
4. Expand to 10k-tick DG1 acceptance test
5. Measure MSPT baseline vs. Nebula

**ETA**: 3-5 days

---

### Phase 3: Make It Fast (Priority: P1)

**Goal**: Optimize NMS sync overhead to meet 30% MSPT reduction target.

**Tasks**:
1. Profile `executeOwnedDag` execution time breakdown
2. Deduplicate syncFromNms/syncToNms by WorldPos
3. Implement batch NMS operations
4. Only sync positions that actually changed
5. Re-measure MSPT

**ETA**: 3-5 days

---

### Phase 4: Expand Scope (Priority: P2)

**Goal**: Entity subsystem integration and DG2 acceptance.

**Tasks**:
1. Wire EntityTickExecutor into real tick path
2. Test with live entities (animals, monsters)
3. 50k-tick entity zero-diff test
4. Random budget verification under load

**ETA**: 5-7 days

---

## Risk Assessment

### High-Risk Items

1. **GlobalRegionScheduler timing**: May be out of sync with region tick loops
   - **Mitigation**: If fails, inject via ASM into `RegionizedWorldServer.tick()`

2. **Java 25 NMS compatibility**: Runtime may differ from compile-time assumptions
   - **Mitigation**: Extensive logging, fallback to shadow executor

3. **NMS bridge overhead too high**: May not achieve 30% MSPT reduction
   - **Mitigation**: Optimize batch operations, consider caching strategies

4. **Agent injection fragile**: May break on Folia updates
   - **Mitigation**: Add runtime verification (sentinel fields), graceful degradation

---

## Honest Conclusion

**Nebula is not a playable release.** It's a sophisticated prototype with solid architecture but incomplete integration.

### What We Have
- Strong architectural foundation
- High-quality, well-tested components
- Complete build and deployment toolchain
- Professional documentation structure

### What We Need
- **One successful end-to-end execution** proving the concept works
- Integration test harness
- Performance measurements on real workloads
- Documentation that matches reality

### Time to "Actually Playable"
- **Optimistic**: 2-3 weeks (if B1/B2 fixes work immediately)
- **Realistic**: 4-6 weeks (accounting for unexpected issues)
- **Pessimistic**: 8-12 weeks (if fundamental approach needs revision)

---

## Next Immediate Actions

1. **Deploy B1/B2 fixes to test server** (today)
2. **Place redstone dust and verify DAG executes** (today)
3. **If DAG executes**: Move to Phase 2 (correctness testing)
4. **If DAG fails**: Deep dive into agent interception chain (B3)
5. **Update all documentation** to reflect actual status (ongoing)

---

**Updated by**: Claude Code  
**Reviewed by**: [Pending]  
**Approved by**: [Pending]
