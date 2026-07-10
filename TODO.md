# Nebula Project - TODO List

**Based on**: PROJECT_STATUS.md (source of truth), the whitepaper (docs/nebula-architecture.md) and its two patches (docs/nebula-patch-001/002.md)
**Last verified**: 2026-07-09 — 817 unit tests pass (0 failures, run this session); DG1 redstone slice verified live on Folia 26.1.2
**Branch**: feat/fix-folia-scheduler-v2

---

## 🚨 HONEST SCOPE (updated 2026-07-10)

**Two ways to measure "how far along" — don't conflate them.**

1. **Against the narrow current goal** (prove a deterministic redstone DAG runs on real Folia):
   the redstone slice is largely DONE and live-verified.
2. **Against the whitepaper's full vision** (7 subsystems, ~250 RW annotations, VAP plugin layer,
   T0–T3 tiers): the project remains an early prototype. Redstone is the only subsystem with the
   decisive Paper differential; entity MOVE and block-entity hopper/furnace/dropper slices now run
   live on Folia, but the wider entity/collision, fluid, explosion, AI, light, and VAP scope remains
   partial or unbuilt. This is a **working prototype**, not a near-complete product.

Both are true. The old "~50% complete / 10–18 days remaining" header measured only #1 and is
deleted as misleading — the whitepaper is a multi-year plan, not days of work.

### ✅ Verified on real Folia
- **Redstone end-to-end + single-thread oracle** — live lever→wire→lamp toggles drive the DAG;
  on Paper, the 12-worker DAG matched the single-thread authority 48/48 across two circuits.
- **Deterministic zero-diff capture** — two identical captures → byte-for-byte identical `.nrp` files.
- **Microstep bound + deep live expansion (DG1 Criterion 2)** — `/nebula perf` auto-grades ≤256;
  `/nebula diag` recorded `seedTasks=1 microsteps=14 modified=15` on a cold 15-wire toggle. Depth is
  governed by dirty-set shape, not topology (`MicroStepDepthTest`).
- **DAG shadow-overhead budget (DG1 Criterion 3, redefined Path 2)** — p99 DAG tick 1.914ms < 3ms at
  16 circuits / 238 components / 7097 ticks, auto-graded by `scripts/perf-harness.sh`.
- **Driven live-load zero-diff at DG1 scale** — `zerodiff-harness.sh 10000 8 --drive 42` → 9994/10000
  frames identical after a 6-frame cold-CAS transient. NOTE: this is Nebula-vs-Nebula seed-consistency.
- **DG3 settled-state Folia-vs-Nebula gate** — `divergence-grade.sh --settled 4 --warmup 100` PASSes
  deterministically (0 diverged / 64 positions, 3 consecutive fresh boots, verified 2026-07-09).
- **Entity MOVE slice** — region-threaded live DAG and the RW-guard clean after it caught and fixed
  the swept-descent terrain footprint (`tracedTasks=163`). Vertical-only write-back is built behind a
  defaults-OFF flag but was **not** live-armed in its implementation cycle.
- **Block-entity slices** — hopper/furnace/dropper actions run region-threaded; the block-entity
  RW-guard traced live hopper/furnace field accesses clean (`tracedTasks=48`), BE-SETTLED passed
  multi-region hopper and 3-slot furnace workloads, and the dropper eject phase was measured live.

### ⚠️ Built but only partially verified live
- Entity COLLISION/AI/item/damage task types — code/RW-sets exist, but only MOVE is live-seeded and
  guard-verified; DG2 50k zero-diff is not done.
- Block-entity BREWING and broader DISPENSER behavior — not exercised by the live guard workload;
  block-entity write-back remains intentionally off because the amount-only model is lossy and the
  measured hopper/furnace/dropper offsets are ordering artifacts, not honest write-back gaps.
- Fluid + explosion task factories (`FluidTaskGenerator`, `ExplosionTaskFactory`) — unit-tested only.
- VAP plugin layer (`nebula-core/vap`: ManagedStateProxy, MvccVersionStore, cert levels) — skeleton;
  **no real plugin has ever been run through it**.
- Random budget acceptance — block-entity dropper RNG is consumed live, but the formal entity
  over-budget-rate workload remains unverified.

### ❌ Designed in the whitepaper, essentially UNBUILT
- **Light subsystem** (whitepaper ch.10) — 0 implementation files.
- **Entity AI / pathfinding** (ch.7: Sense/GoalSelect/Pathfind/Act) — 0 implementation files.
- **RW-set coverage** — the determinism theorem *depends* on this; patch-001 calls it the project's
  Achilles' heel. The `@NebulaRW` *annotation* is still applied to **0 methods** — runtime RW-sets live
  instead as hand-built `RWSet` builders in each `*TaskFactory` plus redstone's `ComponentTemplate`
  reference records. Redstone factory-vs-template agreement is tested and its live guard has both clean
  and deliberately-broken detection runs. Entity MOVE and live hopper/furnace block-entity actions are
  also guard-verified. The remaining gap is breadth: collision/AI/item/damage, brewing/dispenser breadth,
  fluid, explosion, and a real method-level hotspot inventory feeding the coverage dashboard. The
  whitepaper's DG3 deliverable remains a "full-system RW library (~250 functions)"; current evidence is
  per-live-task-type, not that full method inventory. See the task breakdown below.
- **Folia-vs-Nebula divergence under sustained LIVE load** — settled-state passes; the driven
  square-wave residual-rate check is a known-limited signal (observe lag, not a bug). See memory
  `divergence-grade-needs-settled-sampling`.
- Phase 1.5 annotation-maintenance toolchain (patch-002); VAP certification pipeline (patch-002 §13.7);
  T2/T3 fidelity tiers; DAG build-timeout degradation; spatial bucketing at scale — all design-only.

### 📌 A note on DG1 Criterion 3 ("≥30% MSPT reduction")
Nebula is **observe-only** in both AGENT and INTERCEPT modes (verified 2026-07-08, commit 4a934bb):
the DAG is a non-authoritative shadow on top of authoritative Folia, so it can only *add* overhead —
there is no serial work it replaces and thus no "reduction" to measure. Criterion 3 was formally
redefined to a shadow-overhead budget (p99 DAG tick < 3ms). Every "≥30% reduction" line below is
**historical only** and superseded by that note in docs/PROJECT_STATUS.md (the source of truth).

---

## PHASE 1: Make DAG Execute on Real Server — ✅ DONE (verified 2026-07-08/09)

**Goal**: Get ONE successful "DAG tick: N tasks, M microsteps" log entry — **ACHIEVED.**
B1/B2/B3 are all resolved (see the blocker table at the bottom). Live lever→wire→lamp toggles
produce exception-free DAG ticks. The unchecked sub-items below are **retained as the verification
record** of how it was proven, not as open work.

### 1.1 Verify B1 Fix - RedstoneTickHook Lifecycle (Day 1)
- [ ] Configure RCON in folia-test-server/server.properties
  ```properties
  enable-rcon=true
  rcon.port=25576
  rcon.password=nebulatest
  ```
- [ ] Start Folia server: `cd folia-test-server && ./start.sh`
- [ ] Check logs for lifecycle registration: `grep "lifecycle driver registered" logs/latest.log`
- [ ] Add diagnostic logging to RedstoneTickHook.beginTick/endTick (see INTEGRATION_TESTING_GUIDE.md)
- [ ] Verify beginTick/endTick are called every tick
- [ ] **Expected**: Alternating beginTick/endTick logs every tick
- [ ] **Risk**: GlobalRegionScheduler may be out of sync with region ticks
- [ ] **Fallback**: ASM inject into RegionizedWorldServer.tick() if scheduler doesn't work

### 1.2 Verify B2 Fix - ComponentMap Population (Day 1)
- [ ] Check initial scan log: `grep "Initial sync scan complete" logs/latest.log`
- [ ] Connect via RCON: `rcon -H localhost -p 25576 -P nebulatest`
- [ ] Run `/nebula status` to check componentMap size
- [ ] Place redstone wire: `setblock 0 72 0 minecraft:redstone_wire`
- [ ] Check if componentMap grows
- [ ] Verify WorldRedstoneScanner.scanBlock() is called on block place
- [ ] **Expected**: componentMap contains placed redstone components
- [ ] **If fails**: Check chunk load events are firing, verify sync scan timing

### 1.3 Verify B3 - Agent Interception Chain (Day 2)
- [ ] Verify agent loads: `grep "Retransformed.*redstone classes" logs/latest.log`
- [ ] Verify sentinel: `grep "NeighborUpdateHooks sentinel verified" logs/latest.log`
- [ ] Add logging to NeighborUpdateHooks.onNeighborUpdate()
- [ ] Place/break redstone, watch for interception logs
- [ ] Check if RedstoneTickHook.dirtyCount() increases
- [ ] Verify RedstoneEventListener also fires (Bukkit API fallback)
- [ ] **Expected**: Both agent AND event listener should detect redstone changes
- [ ] **If agent fails**: RedstoneEventListener provides stable fallback

### 1.4 End-to-End DAG Execution Test (Day 2-3)
- [ ] Place lever: `setblock 0 72 0 minecraft:lever[powered=false]`
- [ ] Place wire: `fill 1 72 0 10 72 0 minecraft:redstone_wire`
- [ ] Place lamp: `setblock 11 72 0 minecraft:redstone_lamp`
- [ ] Toggle lever: `setblock 0 72 0 minecraft:lever[powered=true]`
- [ ] Watch logs: `tail -f logs/latest.log | grep "DAG tick"`
- [ ] **CRITICAL SUCCESS CRITERION**: See "DAG tick: N tasks, M microsteps in Xms"
- [ ] Verify lamp actually lights up
- [ ] Toggle lever off, verify lamp turns off
- [ ] **If DAG doesn't execute**: Systematically debug using INTEGRATION_TESTING_GUIDE.md

### 1.5 Debug and Fix Issues (Day 4-5)
- [ ] If B1/B2/B3 don't work, add extensive diagnostic logging
- [ ] Check FoliaRegionTickExecutor.executeTasks() is called
- [ ] Check executeOwnedDag() receives non-empty task list
- [ ] Verify componentMap snapshot is not empty
- [ ] Check syncFromNms/syncToNms are executing
- [ ] Review server logs for any exceptions or errors
- [ ] **Document all issues found** in PROJECT_STATUS.md

---

## PHASE 2: Verify Correctness (zero-diff) — ✅ DONE for the redstone slice (2026-07-08/09)

**Goal**: Prove the DAG produces correct results (zero-diff property) — **ACHIEVED for redstone.**
DG1 Criteria 1/2/3 are all verified (see the DG1 checklist near the bottom). The remaining
correctness frontier is Folia-vs-Nebula divergence under *sustained live* load (settled-state
passes; driven square-wave is a known-limited signal). Sub-items retained as the verification record.

### 2.1 Create Standard Redstone Test World (Day 6)
- [ ] Design 5 test circuits:
  1. 16-block straight wire (signal propagation)
  2. Redstone torch feedback (oscillator)
  3. Repeater delay line (4 delay settings)
  4. Comparator (compare + subtract modes)
  5. Simple 0-tick pulse generator
- [ ] Create circuits via RCON `/setblock` commands
- [ ] Document exact coordinates in test-world-layout.md
- [ ] Take screenshots of each circuit
- [ ] Test each circuit manually (toggle inputs, verify outputs)

### 2.2 Small-Scale Zero-Diff Test (Day 7)
- [ ] Start with 1k ticks (not 10k - iterate faster)
- [ ] Run `/nebula capture start 1000`
- [ ] Let it run for ~50 seconds (1k ticks @ 20 TPS)
- [ ] Run `/nebula capture stop`
- [ ] Check frame count matches 1000
- [ ] Run twice, compare state hashes
- [ ] **Expected**: All 1000 frame hashes identical between runs
- [ ] **If fails**: This is a CRITICAL correctness bug - investigate before scaling up

### 2.3 DG1 10k-Tick Zero-Diff Test (Day 8)
- [ ] Run `/nebula capture start 10000`
- [ ] Wait ~8 minutes (10k ticks @ 20 TPS)
- [ ] Run `/nebula capture stop`
- [ ] Verify all 10,000 frame hashes match between runs
- [ ] **DG1 Criterion 1**: PASS if zero diff
- [ ] Document any hash mismatches or failures
- [ ] If fails: This blocks DG1 acceptance - must fix before proceeding

### 2.4 Microstep Count Verification (Day 9)
- [ ] Analyze DAG tick logs: `grep "DAG tick" logs/latest.log | awk '{print $4}'`
- [ ] Find maximum microstep count across all ticks
- [x] **DG1 Criterion 2**: Microsteps ≤ 256 per tick — **VERIFIED AT SCALE 2026-07-08** via
      `MicroStepRecorder` + `/nebula perf` (max 1 over 7200 driven DAG ticks)
- [ ] If exceeds 256: Investigate why (circuit design or algorithm issue)
- [x] Document microstep distribution (min/avg/max/p95/p99) — now on the `/nebula perf` surface
- [x] Establish what drives microstep depth — **dirty-set shape, not topology** (`MicroStepDepthTest`,
      2026-07-08): single leading-edge seed → ~14 microsteps; whole-line seeded → ≤1
- [x] Live deep-expansion demo — **DONE 2026-07-09**. The pipeline already single-task-seeds
      (`FoliaRegionTickExecutor` dispatches `List.of(task)`), confirmed by `/nebula diag` logging
      `seedTasks=1` on every invocation. A cold toggle of a 15-wire line recorded `seedTasks=1
      microsteps=14 modified=15` — deep live cascade from a single seed. The earlier max=1 was the
      settled-state case (`cas=0→nms=0` on re-toggle). Caveat closed with evidence.

### 2.5 DAG Shadow-Overhead Measurement (Day 10)
> Reframed 2026-07-08: this is **added overhead**, not a "reduction." Nebula is
> observe-only, so measuring `MSPT_nebula - MSPT_vanilla` gives the DAG shadow's cost
> on top of Folia; it is additive by construction and cannot go negative. See the
> DG1 Criterion 3 note in docs/PROJECT_STATUS.md.
- [ ] Baseline: run vanilla Folia (no Nebula plugin) with the test circuits; record avg MSPT
- [ ] Restart with Nebula plugin; record avg MSPT (use `/nebula perf`, not just Spark)
- [ ] Compute added overhead: `MSPT_nebula - MSPT_vanilla` (expect a positive number)
- [x] **DG1 Criterion 3 (redefined — Path 2, decided 2026-07-08):** grade against the
      shadow-overhead budget "p99 DAG tick < OVERHEAD_BUDGET_MS (default 3ms)" —
      AUTO-GRADED with a PASS/FAIL exit code by `scripts/perf-harness.sh`. **VERIFIED at scale
      2026-07-08**: large run p99 1.914ms over 7097 ticks (16 circuits / 238 components); small
      run p99 2.002ms. Budget tightened 5ms→3ms since both landed ~2ms
- [ ] Document actual overhead for Phase 3 optimization planning

---

## PHASE 3: Minimize DAG Shadow Overhead (P2 — optional, not blocking)

**Goal**: Minimize the DAG shadow's *added* overhead (there is no "reduction" to achieve —
Nebula is observe-only; see the DG1 Criterion 3 note in docs/PROJECT_STATUS.md)

### 3.1 Profile executeOwnedDag() (Day 11)
- [ ] Add timing instrumentation to each phase:
  ```java
  long t1 = System.nanoTime();
  // Phase 1: syncFromNms
  long t2 = System.nanoTime();
  // Phase 2: DAG execution
  long t3 = System.nanoTime();
  // Phase 3: syncToNms
  long t4 = System.nanoTime();
  LOG.info("Phase 1: " + (t2-t1)/1000000 + "ms, Phase 2: " + (t3-t2)/1000000 + "ms, Phase 3: " + (t4-t3)/1000000 + "ms");
  ```
- [ ] Run test workload, collect timing data
- [ ] Identify bottleneck phase (likely Phase 1 or 3 - NMS sync)
- [ ] Measure per-position sync overhead
- [ ] Calculate total time spent in syncFromNms vs DAG vs syncToNms

### 3.2 Optimize NMS Sync - Deduplication (Day 12-13)
- [ ] Problem: Multiple tasks may reference same WorldPos
- [ ] Solution: Deduplicate ownedTasks by WorldPos before sync
  ```java
  Set<WorldPos> uniquePositions = ownedTasks.stream()
      .map(POSITION_OF)
      .collect(Collectors.toSet());
  for (WorldPos pos : uniquePositions) {
      blockBridge.syncFromNms(world, pos);
  }
  ```
- [ ] Measure improvement: log reduction in sync calls
- [ ] Re-run MSPT test, calculate improvement

### 3.3 Optimize NMS Sync - Change Detection (Day 14)
- [ ] Problem: syncToNms() writes even when power level unchanged
- [ ] Solution: Only write if power level actually changed
  ```java
  int oldPower = casStore.getPowerLevel(pos);
  int newPower = redstoneState.getPowerLevel(pos);
  if (oldPower != newPower) {
      blockBridge.syncToNms(world, pos, newPower);
  }
  ```
- [ ] Measure write reduction percentage
- [ ] Re-run MSPT test

### 3.4 Optimize NMS Sync - Batch Operations (Day 15)
- [ ] Investigate if Bukkit API supports batch block updates
- [ ] If yes: Implement bulkSyncToNms() in NmsBlockStateBridge
- [ ] If no: Document limitation, skip this optimization
- [ ] Re-run MSPT test

### 3.5 Final MSPT Measurement (Day 16)
- [ ] Run all optimizations together
- [ ] 5-minute MSPT test with Spark
- [ ] Calculate final added overhead vs baseline
- [ ] **Target**: minimize added overhead (the ≥30% *reduction* target is unreachable for an
      observe-only shadow — see DG1 Criterion 3 note in docs/PROJECT_STATUS.md)
- [ ] Nebula is observe-only, so overhead is expected and additive by construction
- [ ] Document actual result, plan further optimizations if needed

### 3.6 Add Guards and Error Handling (Day 17)
- [ ] componentMap empty guard (already done, verify it works)
- [ ] DAG execution timeout (e.g., abort if > 50ms)
- [ ] Microstep overflow: graceful degradation if > 256
- [ ] CAS commit failure rate monitor (log if > 1%)
- [ ] Add configurable logging levels (INFO/DEBUG/TRACE)

---

## PHASE 4: Entity Subsystem Integration (P2 — next major milestone after redstone)

**Goal**: Wire EntityTickExecutor and pass DG2 acceptance

### 4.1 Entity Task Generation Verification (Day 18)
- [ ] Review EntityTaskFactory.java logic
- [ ] Add unit tests if missing
- [ ] Verify MOVE/COLLISION action generation
- [ ] Check EntityStateSnapshot captures position/velocity correctly

### 4.2 Wire EntityTickExecutor into Real Tick Path (Day 19)
- [ ] Decide: Separate tick path or integrate into executeOwnedDag()?
- [ ] Modify NebulaPlugin to collect entity positions
- [ ] Call EntityTickExecutor.executeTick() for entity-dirty regions
- [ ] Verify NmsEntityStateBridge.syncFromNms/syncToNms work

### 4.3 Entity Zero-Diff Test Setup (Day 20)
- [ ] Spawn test entities in world (10 sheep, 5 zombies, 5 skeletons)
- [ ] Run 1k-tick entity capture (start small)
- [ ] Verify entity state hashes are captured
- [ ] Check for any exceptions or errors

### 4.4 DG2 50k-Tick Entity Zero-Diff Test (Day 21-22)
- [ ] Run `/nebula capture start 50000`
- [ ] Wait ~40 minutes (50k ticks @ 20 TPS)
- [ ] Check frame count: 50,000
- [ ] Verify zero diff across two runs
- [ ] **DG2 Criterion 1**: PASS if zero diff
- [ ] Document any entity-specific issues

### 4.5 Random Budget Verification (Day 23)
- [ ] Check RandomBudget implementation in code
- [ ] Run high-load entity test (100+ entities)
- [ ] Monitor over-budget events in logs
- [ ] **DG2 Criterion 2**: Over-budget rate < 1%
- [ ] Verify graceful degradation when over-budget

### 4.6 CompositeTaskRunner Integration (Day 24)
- [ ] Verify redstone + entity tasks route correctly
- [ ] Test mixed workload (redstone + entities together)
- [ ] Check for cross-subsystem interference
- [ ] Measure MSPT with combined load

---

## PHASE 5: Full System & Acceptance (P3 — long-horizon, whitepaper scope)

**Long-term tasks; most of this is design-only today (light, AI, VAP-with-real-plugins, load testing).**

### 5.1 DG3 Full System Zero-Diff
- [ ] Combine redstone + entity + tile entities
- [ ] Run 100k-tick capture (longer than DG1/DG2)
- [ ] Verify zero diff for full system
- [ ] **DG3 Criterion 1**: PASS

### 5.2 VAP Plugin Compatibility Testing
- [ ] Research mainstream Folia plugins
- [ ] Install top 10 plugins on test server
- [ ] Verify no conflicts with Nebula
- [ ] Measure compatibility rate
- [ ] **DG3 Criterion 2**: Level 0 compatibility ≥ 80%

### 5.3 Load Testing Infrastructure
- [ ] Research/build simulated player system for Folia
- [ ] Design 100-player load test scenario
- [ ] Implement player simulation scripts

### 5.4 100-Player Load Test
- [ ] Run 100 simulated players
- [ ] Monitor TPS over 30 minutes
- [ ] Check for crashes, memory leaks, performance degradation
- [ ] **DG3 Criterion 3**: Maintain 20 TPS with 100 players

---

## TECHNICAL DEBT & POLISH (P2 - Ongoing)

### Build & Environment
- [ ] Fix gradle.properties JDK path hardcoding (B7)
  - Use environment variables
  - Add local.properties support (gitignored)
  - Document build requirements in README
- [ ] Add Windows build instructions
- [ ] Test build on different Linux distros

### Code Quality
- [ ] Expand @NebulaRW annotations from 7 to ~200 (B8)
  - Identify all methods accessing shared state
  - Add annotations with read/write sets
  - Update coverage dashboard
- [ ] Add more unit tests for edge cases
- [ ] Code review all NMS bridges
- [ ] Refactor any complex methods (> 100 lines)

### Documentation
- [ ] Keep PROJECT_STATUS.md updated as progress is made
- [ ] Document all bugs found during integration testing
- [ ] Write troubleshooting guide based on actual issues encountered
- [ ] Create architecture decision records (ADRs) for major choices
- [ ] Update README with realistic status and timeline

### Monitoring & Observability
- [ ] Add metrics collection (task count, microsteps, execution time)
- [ ] Implement /nebula metrics command
- [ ] Add configurable logging levels
- [ ] Create dashboard for monitoring DAG health

---

## RISKS & MITIGATION

### High-Risk Items Requiring Monitoring

1. **GlobalRegionScheduler Timing (B1)**
   - Risk: Out of sync with region ticks
   - Monitor: Check if beginTick/endTick timing matches actual redstone events
   - Fallback: ASM inject into RegionizedWorldServer.tick()

2. **Agent Fragility (B3)**
   - Risk: Breaks on Folia version updates
   - Monitor: Test on each new Folia release
   - Fallback: RedstoneEventListener provides stable Bukkit API path

3. **NMS Bridge Overhead (B4)**
   - Risk: DAG shadow adds too much per-tick overhead on large circuits
   - Monitor: Phase 3 profiling will reveal this (measure *added* overhead, not a reduction)
   - Mitigation: Extensive optimization in Phase 3

4. **Java 25 Compatibility**
   - Risk: Subtle runtime differences vs compile-time
   - Monitor: Watch for strange behavior or crashes
   - Mitigation: Extensive logging, fallback to Java 21 if needed

---

## SUCCESS CRITERIA CHECKLIST

### Phase 1 Complete (Minimum Viable Integration) — ✅ ALL MET (2026-07-08/09)
- [x] Server starts without errors
- [x] Plugin loads successfully
- [x] Agent retransform succeeds
- [x] Hook sentinel verification passes
- [x] componentMap populated with redstone
- [x] RedstoneTickHook lifecycle active (beginTick/endTick called)
- [x] **"DAG tick: N tasks, M microsteps" appears in logs** ⭐ CRITICAL
- [x] Lever → wire → lamp circuit works correctly

### DG1 Complete (Redstone Subsystem)
- [x] 10k-tick zero-diff test passes — VERIFIED 2026-07-08 (static world; `scripts/zerodiff-harness.sh`)
- [x] Microsteps ≤ 256 per tick — VERIFIED AT SCALE 2026-07-08, and deep live expansion VERIFIED
      2026-07-09 (`/nebula diag`: `seedTasks=1 microsteps=14 modified=15` on a cold 15-wire toggle;
      `/nebula perf` recorded max 14). Pipeline single-task-seeds (`FoliaRegionTickExecutor` dispatches
      `List.of(task)`, confirmed by `seedTasks=1` per line); earlier max=1 was the settled-state case
      (`cas=0→nms=0` on re-toggle) — see DG1 verdict in docs/PROJECT_STATUS.md
- [x] Criterion 3 (redefined, Path 2): p99 DAG shadow overhead < 3ms under a large
      multi-region workload, auto-graded by `scripts/perf-harness.sh` — **VERIFIED 2026-07-08**
      (large run: p99 1.914ms, 7097 ticks, 16 circuits / 238 components) — see DG1 Criterion 3
      note in docs/PROJECT_STATUS.md

### DG2 Complete (Entity Subsystem)
- [ ] 50k-tick entity zero-diff test passes
- [ ] Random over-budget rate < 1%

### DG3 Complete (Full System)
- [ ] Full system zero-diff test passes
- [ ] VAP plugin compatibility ≥ 80%
- [ ] 100-player load test at 20 TPS

---

## BLOCKER STATUS (updated 2026-07-09)

### P0 — all RESOLVED and live-verified
- [x] B1: RedstoneTickHook lifecycle — **RESOLVED** (GlobalRegionScheduler driver; DAG ticks fire live)
- [x] B2: componentMap empty — **RESOLVED** (sync scan + `/nebula scan`; 64 components registered in gate)
- [x] B3: Agent interception chain — **RESOLVED** (world-name key mismatch fixed, commit cb223fe;
      `BlockRedstoneEvent` fallback also confirmed firing)
- [x] Phase 1.4: End-to-end DAG execution — **ACHIEVED** (live circuit toggles → DAG ticks)

### P1 — DG1 redstone criteria all met
- [x] B4: DAG shadow overhead — **MEASURED & within budget** (p99 1.914ms < 3ms at scale). Note: this
      is *added* overhead, not a reduction (observe-only). Further optimization is optional, not blocking.
- [x] B5: Redstone test world — circuits placed via RCON / `scripts/*` harnesses
- [x] B6: Capture harness E2E — **VERIFIED** (byte-for-byte identical `.nrp` files)

### P2 — open technical debt
- [ ] B7: Build environment portability — `gradle.properties` hardcodes JDK paths (Linux-only build)
- [ ] B8: **RW-set coverage & verification** — redstone complete (27/27), but 0 annotations are
      runtime-verified and entity/BE/fluid/explosion/AI RW-sets are per-task-type + not live-wired.
      Highest-leverage open item; the determinism theorem depends on it (patch-001). An epic — slice
      it subsystem by subsystem. **Full breakdown: see the "@NebulaRW / RW-SET COVERAGE" section below.**

---

## HONEST NEXT ACTIONS (2026-07-09)

The redstone DG1 slice is done and live-verified. There is **no short path to "playable"** — the
whitepaper scope is years of work. Pick the next slice by honest value, not by a countdown.

**Highest-leverage open work, in rough priority order:**

0. **Live Paper differential harness (B9)** — the ONE test we had never run: diff Nebula's DAG output
   against a **single-thread** MC server, the actual oracle the project's core claim names ("multi-thread
   == single-thread result"). D1–D4 are DONE: **D4 LIVE-VERIFIED 2026-07-10 — `/nebula diff` reported
   matched 16/16 on real Paper 26.1.2**, the first single-thread oracle comparison (non-trivial: shadow
   CAS held the full 15→1 decay gradient). **The one remaining B9 task is D5**: D2's executor runs the DAG
   INLINE on the main thread, so D4 proves shadow==authority but NOT the *parallel* claim. D5 must run the
   DAG layers across a real `nebula-core` worker pool (not inline), re-run the D4 circuit + toggle +
   `/nebula diff`, confirm still matched==total, then scale to multiple independent circuits in different
   chunks so the pool genuinely runs tasks concurrently. THAT is the decisive experiment for "multi-thread
   Nebula == single-thread MC." See the **"LIVE PAPER DIFFERENTIAL HARNESS"** D5 task below.
1. **Push the DG3 settled-state gate harder** (cheap, high-signal). It passes at 4 dust-only
   circuits; raise circuit count (8/16) and add repeaters + comparators so it exercises multi-power-
   level convergence, not just 15→0 decay. If that holds, settled-state correctness is a solid
   standing signal. (This is the `Next:` handoff from commit b38e27f.)
2. **Folia-vs-Nebula divergence under sustained live load** — the last correctness gap driven mode
   does not prove. Needs settled-state sampling, not the known-limited square-wave residual rate
   (see memory `divergence-grade-needs-settled-sampling`).
3. **RW-set coverage & verification (B8)** — the widest designed-vs-done gap and the theorem's
   precondition. Redstone is complete (27/27) but *nothing* is runtime-verified. Full cycle-sized task
   breakdown is in the **"@NebulaRW / RW-SET COVERAGE"** section below; start with A1 (drift test) and
   B1–B2 (make the guard work, then run it live).
4. **Verify the RW-Set Integrity Guard against real NMS** (patch-001 P0) — the API exists but its
   bytecode tracer has never run against real Folia access. Until it does, annotation completeness
   is unchecked in practice.
5. **Wire the entity DAG into the live tick path** — `nebula-entity` is built and unit-tested but
   never runs live. First live entity DAG tick is the DG2 analogue of the redstone milestone.

**Lower priority / larger:** fluid + explosion live integration; light & AI/pathfinding subsystems
(unbuilt); VAP with a real plugin; B7 build portability.

**Ground rules that keep this honest** (from ralph_prompt.txt): never upgrade a claim past what was
verified *this session*; if a change touches the tick pipeline, a green unit test is not proof — run
the live-Folia decisive experiment; one scoped, committed slice at a time.

---

## @NebulaRW / RW-SET COVERAGE — full task breakdown (B8, the Achilles' heel)

**Why this is the top structural task.** patch-001 (`docs/nebula-server-patch-001.md`) names RW-set
completeness the project's *阿喀琉斯之踵* (Achilles' heel): if a task's declared read/write set omits a
real access, the DAG loses a dependency edge, two tasks that should serialize run in parallel, and state
corrupts non-deterministically — the exact "ghost bug" T0 mode promises to prevent. The whitepaper's DG3
deliverable (`docs/nebula-architecture.md:1217`) is a **full-system RW library of ~250 functions**.

**Current audited state (2026-07-10 — read before trusting older "3%" claims):**
- The `@NebulaRW` *annotation type* (`nebula-core/.../annotations/NebulaRW.java`) is applied to **0 methods**.
  Real RW-sets are hand-built `RWSet` objects in the `*TaskFactory` classes (the live path), mirrored by
  `ComponentTemplate` reference records for redstone.
- Redstone's two representations are pinned field-by-field by tests; other subsystems do not yet have a
  real method-level hotspot inventory or equivalent template-maintenance surface.
- The runtime guard is live-proven: redstone has clean + deliberately-broken detection runs; entity MOVE
  traced clean after a live violation fixed its terrain footprint; live hopper/furnace block-entity field
  accesses traced clean. Runtime verification is therefore real but partial, not system-wide.

**Per-subsystem status (methods with a real RWSet today):**

| Subsystem            | RW-sets present                    | Live-wired | Runtime-verified | Gap |
|----------------------|------------------------------------|-----------|------------------|-----|
| Redstone             | ✅ 27/27 component types + templates | ✅ yes | ✅ block accesses | method-level inventory still absent |
| Entity (`nebula-entity`) | ⚠️ per task-type, 7 types | ⚠️ MOVE only | ✅ MOVE only | collision/AI/item/damage remain unlive |
| Block-entity         | ⚠️ per task-type, 6 types | ⚠️ hopper/furnace/dropper slices | ✅ live hopper/furnace field accesses | brewing/dispenser breadth unverified |
| Fluid                | ⚠️ per task-type, 4 types           | ❌ no      | ❌ no            | wire + guard at small scale |
| Explosion            | ⚠️ per task-type, 5 types           | ❌ no      | ❌ no            | wire + guard at small scale |
| Entity AI/pathfinding | ⚠️ per task-type, 5 types (`AITaskFactory`) | ❌ no | ❌ no | ch.7 subsystem largely design-only |
| Light (ch.10)        | ❌ 0 files                          | ❌         | ❌               | unbuilt |

### Tasks — ordered so each is ONE verifiable Ralph cycle

Do these top-to-bottom; each is scoped to a single commit. Prefer the cheap high-signal ones first
(they de-risk everything below them). A ⚡ marks a task that touches the live tick pipeline and therefore
requires the decisive Folia experiment, not just a green unit test.

**A. Close the drift between the two RW representations (cheap, pure nebula-core/redstone, no Folia)**
- [x] A1. Add a test asserting each redstone `ComponentTemplate` agrees field-by-field with the live
      `RedstoneTaskFactory` RWSet for the same type (block footprint, globals, events). Today only
      `size()` is asserted equal — the *contents* can diverge silently. This is the guardrail that keeps
      redstone honest as new types land. (Extends `RedstoneTaskFactoryTest`.) — **DONE** (commit 16a77dd,
      "pin the factory-vs-annotation RW-set agreement for all 27 redstone components").
- [x] A2. Audit the 7 remaining orphaned method constants in `RedstoneAnnotations`
      (`WIRE_GET_SIGNAL`, `WIRE_GET_DIRECT_SIGNAL`, `WIRE_TURBO_SHAPE`, `REPEATER_GET_SIGNAL`,
      `WEIGHTED_PRESSURE_PLATE_SIGNAL_FOR_STATE`, `COLLECTING_NEIGHBOR_UPDATER`, `SCULK_SENSOR_TICK`).
      For each: either fold its read/write facts into the owning component's template, or delete it as a
      dead reference. Leave a comment recording the decision so it doesn't get re-added. — **DONE**
      (commit d38705d): the five getSignal-family constants folded into their owning templates
      (wire/repeater/pressure-plate); `CollectingNeighborUpdater.runNext` and `SculkSensorBlock.tick`
      kept as documented exceptions in `KNOWN_UNWIRED_METHOD_REFS`; a new orphan-guard test fails the
      build on any future unjustified orphan. A3 (SculkSensor home) now RESOLVED below.
- [x] A3. Decide SculkSensor's home. **DONE (2026-07-09): home is the game-event / vibration subsystem,
      NOT the redstone DAG — so NO `RedstoneComponentType` value was added.** The redstone DAG is seeded
      only by BLOCK_UPDATE / neighbour-update interception; a sculk sensor is vibration-triggered and its
      phase transitions run off scheduled ticks, so it fires no BLOCK_UPDATE that could ever seed a redstone
      task — a `SCULK_SENSOR` type would be un-seedable dead code (the exact doc-vs-reality drift this
      project fights). Its output power edge needs no dedicated task: an ACTIVE sensor powering a neighbour
      already seeds the *neighbour's* redstone task via the normal path. `SCULK_SENSOR_TICK` stays a
      documented reference constant (the RW-set the future game-event subsystem will consume). Decision
      recorded on the `RedstoneAnnotations.SCULK_SENSOR_TICK` javadoc and the maintenance-test allow-list.
      Also preserves the `templates().size() == enum length` invariant.

**B. Prove the guard actually works (unblocks all runtime verification — do before B/C annotation sweeps)**
- [x] B1. Unit-drive the RW-guard end to end in `nebula-guard-api`: run a task whose declared RWSet
      deliberately omits one access, assert `RWSetConsistencyChecker` flags exactly that violation with
      the right `AccessTarget`. Confirms components A/B/C are wired together before trusting them live.
      — **DONE**: `RWGuardTaskRunnerTest.wiredGuardFlagsExactlyTheOmittedAccessWithTheRightTarget`
      drives the full runner→`DagExecutor`→`ThreadLocalAccessTrace`→`RWSetConsistencyChecker`→
      `RWGuardReportWriter` path and asserts the surfaced violation is *exactly* the omitted access
      (`UNDECLARED_READ` on `AccessTarget.entityField(health)`, declared `position` NOT flagged) with the
      right task id/type/tick. Companion `wiredGuardReportsNoViolationsWhenTraceMatchesDeclaredSet` proves
      the wired path doesn't cry wolf (ENFORCE mode completes cleanly on a fully-declared trace). The
      prior `dagExecutorCanRunTasksThroughRwGuard` only asserted violation *count* + JSON substrings, so a
      wrong-target bug could slip through the live path. Guard components A/B/C now proven wired together.
- [x] ⚡ B2. Wire `RWGuardTaskRunner` into the live redstone executor behind `-Dnebula.rw.guard=true`
      (low sample rate), deploy, toggle a lever→wire→lamp line, and confirm the tracer records real NMS
      block accesses with **zero** violations against the (known-complete) redstone RW-sets. This is the
      first time the Achilles'-heel protection runs against real Folia — patch-001's core promise. If it
      reports violations on *correct* redstone sets, the tracer itself is wrong; fix that first.
      **B2a DONE (pure-unit bridge slice, 2026-07-09)** — the literal instruction had a FALSE PREMISE:
      `RWGuardTaskRunner` traces `task.action().execute()`, but live redstone tasks are
      `RedstoneTaskFactory.inert(...)` (no-op action); real logic runs through `RedstoneTaskRunner` +
      action registry, whose accesses flow through `RedstoneAccessTracer`, a hook NEVER connected to the
      guard's `ThreadLocalAccessTrace`. Dropping the guard runner into `executeOwnedDag` as-is would trace
      nothing and report a false "zero violations". Built the missing bridge
      (`nebula-plugin/.../RedstoneRwGuardTracer` — forwards `RedstoneAccessTracer` → `ThreadLocalAccessTrace`)
      and unit-proved (`RedstoneRwGuardBridgeTest`, 5 tests): wire/torch/repeater/comparator run through the
      REAL runner+action+context with the bridge tracer trace CLEAN against their REAL factory RW-sets, plus
      a negative control (drop the +X wire neighbour read → checker flags exactly it). "Redstone RW-sets are
      known-complete" is now checker-verified for block accesses, not just asserted.
      **B2b DONE (⚡ LIVE-VERIFIED 2026-07-09):** the FIRST TRUE live guard run — patch-001's
      Achilles'-heel protection ran against real Folia. Seam chosen: a per-task boundary, not the whole
      tick. `RedstoneTaskRunner` (which `MicroStepScheduler` requires by concrete type for `commitLayer`)
      now fires a decoupled `RedstoneTaskGuardHook` around each dispatched task's execution;
      `NebulaPlugin` installs `RedstoneRwGuardTracer.INSTANCE` + `RedstoneRwGuardHook` on that runner ONLY
      when `-Dnebula.rw.guard=true` (sampling via `-Dnebula.rw.guard.sample`, default 1.0). The hook resets
      `ThreadLocalAccessTrace` before each task and runs `RWSetConsistencyChecker` against THAT task's
      declared RW-set after, appending violations to `plugins/Nebula/rw-violations.jsonl`; `executeOwnedDag`
      logs a one-line `RW-GUARD: tracedTasks=N violations=M` summary. Live result on a lever→8-wire→lamp
      toggle: `tracedTasks=940 violations=0 (clean)`, no `rw-violations.jsonl` written. Ruled out the
      false-clean-from-empty-trace mode: concurrent `CASCADE-DIAG` showed the traced wire tasks really
      wrote power (e.g. `x=3 cas=12→nms=13 modified=1`) through the traced `RedstoneTaskContext` write path,
      so `violations=0` is a real verdict on tasks that genuinely accessed blocks. Compound (SCC) tasks are
      checked union-vs-merged-set (sound for a zero-violations confirmation; per-member detection power is
      unit-proven in `RedstoneRwGuardBridgeTest`). NOTE: block-entity/entity/global/random accesses are NOT
      routed through `RedstoneTaskContext` yet, so this verifies block-level reads/writes only (all the
      redstone actions perform today). Unit-guarded by `RedstoneRwGuardHookTest` (3 tests).
- [x] ⚡ B3. Deliberately break one redstone RWSet (drop a neighbour read), re-run B2b, confirm the guard
      catches it live and `RWGuardReportWriter` emits an actionable report (coords + stack + declared set).
      Revert the break in the same cycle. This proves the guard has real detection power, not just a
      green no-op path. (The pure-unit analogue is already covered by
      `RedstoneRwGuardBridgeTest.bridgeHasRealDetectionPowerWhenAnAccessIsUndeclared`.)
      **DONE (⚡ LIVE-VERIFIED 2026-07-09):** dropped the +X neighbour read from `wireRw()` in
      `RedstoneTaskFactory`, built the shaded jar on Java 21, deployed to folia-test-server, launched
      Folia 26.1.2 with `-Dnebula.rw.guard=true`, built a lever→9-wire→lamp line, `/nebula scan` (64
      components), and TOGGLED the lever 5×. The guard caught it LIVE: `RW-GUARD: tracedTasks=5488
      violations=5147`, one WARN per violating wire task, and `plugins/Nebula/rw-violations.jsonl` grew
      to 5147 lines — EVERY line `violation_type=UNDECLARED_READ`, each `REDSTONE_WIRE@x` flagging exactly
      its dropped +X neighbour (`x=1`→reads `x=2`, … `x=8`→reads `x=9`/lamp), with the declared set
      (self + 5 of 6 neighbours, +X missing), a full stack trace through
      `RedstoneRwGuardHook.afterTask`→`RWSetConsistencyChecker.check`, and
      `suggested_fix":"Add block read to REDSTONE_WIRE: WorldPos[... x=2, y=-59, z=0]"`. This is the
      complement of B2b's clean run: the guard has REAL detection power on live Folia, not just a green
      no-op path. Server stopped cleanly via RCON; temp launch harness removed; break reverted in the same
      cycle (`RedstoneTaskFactory` byte-identical to HEAD, tests cached green).
      **FOLLOW-UP (separate task, not B3) — DONE (unit-fixed 2026-07-09):** the `access_target` BLOCK
      object in the JSONL was malformed — `"dimension":WorldPos[dimensionId=0,"x": x=1,...]` — because
      `RWSetViolationJson.accessTarget()` split `AccessTarget.value()` on `,` assuming a bare `dim,x,y,z`
      but the value is now the full `WorldPos.toString()`. Fixed by pulling the four signed ints out of
      the value with a regex (`parseBlockCoords`, tolerant of both the `WorldPos[...]` record form and the
      legacy bare form), and by making `extractIntField` match a *signed* integer so negative coords
      (`y=-59`) round-trip. `access_target` now emits clean `{"type":"BLOCK","dimension":0,"x":2,"y":-59,"z":0}`
      and `RWSetViolationJsonTest.blockAccessTargetEmitsCleanIntFieldsAndRoundTrips` proves toJson→fromJson
      yields the same `AccessTarget.block`. Pure-unit fix in nebula-guard-api; no live Folia run needed.

**C. Extend & verify coverage subsystem by subsystem (each ⚡ needs the guard from B live)**
- [x] ⚡ C1. Wire the **entity** DAG into the live tick path for entity-dirty regions — the DG2
      analogue of the first redstone DAG tick. **DONE for MOVE (2026-07-10):** region-threaded MOVE
      tasks run live in OBSERVE mode with syncFromNms and terrain reads; vertical-only write-back is
      built behind a defaults-OFF flag but its armed path remains not live-verified. COLLISION/AI/item/
      damage remain unseeded.
- [x] ⚡ C2. Run the guard against live entity movement; reconcile every violation into `EntityTaskFactory`
      RW-sets until a moving-mob workload traces clean. Use `AnnotationCoverageDashboard.report(...)` to
      record entity coverage as annotated/total once the hotspot method list is known.
      **DONE (2026-07-10, LIVE-VERIFIED on Folia 26.1.2):** added the missing entity guard bridge
      (`EntityAccessTracer` on `EntityTaskContext` for entity-field + terrain-block reads,
      `EntityTaskGuardHook` at the exact runner boundary, plugin bridges to
      `ThreadLocalAccessTrace` / `RWSetConsistencyChecker`, opt-in via the existing
      `-Dnebula.rw.guard=true`). The first live run had REAL detection power: a fast-falling cow
      produced 9 `UNDECLARED_READ` violations on terrain cells 2 blocks below the task snapshot,
      proving `EntityMoveAction.sweepDescent` can read beyond the old self+6-neighbour declaration.
      Reconciled `moveRw()` to declare the bounded five-cell descent column plus horizontal/upper
      neighbours. Rebuilt/redeployed and re-ran a cow fall from y=120 in OBSERVE mode:
      `tracedTasks=163 violations=0 (clean)`, zero entity violation warnings, no JSONL report,
      and the first entity tick ran on `Folia Region Scheduler Thread #0`. Unit negative control
      drops the velocity write and confirms the checker flags exactly that field. Honest scope:
      this verifies the live-wired MOVE action only; COLLISION/AI/item/damage remain unlive and
      therefore unverified. `AnnotationCoverageDashboard` remains C5 because no real NMS hotspot
      method inventory exists yet.
- [x] ⚡ C3. Same loop for **block-entity** (`BlockEntityTaskFactory`: hopper/furnace/dropper).
      **DONE for the live-wired actions (2026-07-10):** commit 9f7aeea installed the production
      `BlockEntityRwGuardTracer` + per-task hook behind `-Dnebula.rw.guard=true`; a real hopper workload
      reached `tracedTasks=48 violations=0`, corroborated by 33 non-empty CAS entries, while the unit
      negative control flags exactly an omitted furnace output-slot write. Subsequent live C3 slices
      exercised multi-region hopper quiescence, the 3-slot furnace inventory branch, autonomous furnace
      timer sampling, and RNG-backed dropper ejects. Honest scope: the clean live guard evidence covers
      hopper/furnace field accesses; brewing and broader dispenser behavior remain runtime-unverified,
      and write-back stays intentionally off because the amount-only inventory model is lossy and the
      measured offsets are ordering artifacts.
- [ ] ⚡ C4. Same loop for **fluid** (`FluidTaskFactory`) and **explosion** (`ExplosionTaskFactory`) now
      that the live redstone/entity-MOVE/block-entity guard loop is proven; these fan out widely, so verify
      at small scale first.
      - [x] **C4 fluid pure slice DONE (2026-07-10):** added a real `FluidContext` + versioned
            `FluidState`/snapshot execution path, canonical flow/remove actions whose block reads/writes
            pass only through that context, and `FluidRwGuardTracer` bridging those accesses into
            `ThreadLocalAccessTrace`. `FluidRwGuardBridgeTest` corroborates a non-empty six-read/two-write
            flow trace is clean against `FluidTaskFactory` and proves detection power by dropping the
            downward write declaration (exactly one `UNDECLARED_WRITE` at `down`). This is pure/unit-only:
            no NMS bridge, task guard hook, tick seeding, or live Folia fluid execution exists yet.
      - [x] **C4 explosion pure slice DONE (2026-07-10):** added a real `ExplosionContext` +
            per-task block/entity snapshots, canonical ray/destroy/damage actions, and
            `ExplosionRwGuardTracer` covering block, entity-field, and random accesses.
            `ExplosionRwGuardBridgeTest` corroborates a non-empty two-block destroy trace
            (reads+writes both blocks, two world-RNG calls) is clean against `ExplosionTaskFactory`,
            then drops one block write and gets exactly one `UNDECLARED_WRITE` at that position.
            This is pure/unit-only: there is still no NMS bridge, task guard hook, seeding, event
            tracing, or live Folia explosion execution.
            Next C4 slice: wire the smaller fluid path live at tiny scale behind the existing guard;
            prove real non-empty fluid accesses clean before attempting explosion fan-out.
- [ ] C5. Populate `AnnotationCoverageDashboard` from a real per-subsystem hotspot inventory (not
      hand-typed numbers) and surface it via a `/nebula coverage` command, so "coverage %" becomes a
      measured signal instead of a doc claim. Targets patch-002's decay goal (<5%/yr).

**Not in scope until the above lands:** AI/pathfinding (ch.7) and light (ch.10) RW-sets — those
subsystems are essentially unbuilt; annotating them is premature before the guard-verified loop exists.

**Definition of done for B8:** every live-wired subsystem traces clean under the RW-guard on a
representative workload, a factory-vs-template drift test guards redstone, and the coverage dashboard
reports measured (not asserted) per-subsystem ratios. "~250 functions" is the whitepaper's DG3 finish
line; the honest interim goal is **every subsystem that runs live is guard-verified complete.**

---

## LIVE PAPER DIFFERENTIAL HARNESS — prove multi-thread Nebula == single-thread MC (B9)

**Why this exists.** Nebula's whole reason to exist is the claim: *a multi-threaded (DAG-parallel)
server produces the SAME result as a single-threaded vanilla MC server.* Every verification we have so
far is either Nebula-vs-Nebula seed-consistency or Nebula-vs-Folia (Folia is already multi-threaded, so
it is not the single-thread oracle the claim is about). We have never diffed Nebula's parallel DAG
output against an authoritative **single-thread** MC tick. This section builds that differential harness.

**Why Paper (not Folia) is the right host.** Paper is single-threaded, so it *is* the oracle the claim
names. It also runs everything on the main thread, where block **reads are legal** — on Folia
`execute if block` / `data get block` NPE off the region thread, which is why all prior live checks were
write-plus-log-scrape. On Paper we can read BOTH the authoritative block state AND Nebula's CAS-computed
shadow state on the same thread and diff them directly. The multi-threading under test is then Nebula's
own DAG executor thread-pool computing the same circuit that Paper resolves serially — not the server's
region threads.

**TWO BLOCKING FACTS found 2026-07-09 (read before starting — "just run it on Paper" does NOT work):**
1. **Runtime detection misfires on Paper.** `FoliaRuntimeDetector.isFoliaRuntime()` probes for the
   `RegionScheduler` *API class*, which **Paper also ships** — so on Paper it returns `true`, takes the
   Folia path, and attempts the agent NMS retransform. The stricter `isFoliaServer()` (probes the
   server-internal `RegionizedServer`, absent on Paper) already exists in `FoliaRuntimeDetector` but is
   **unused** in `NebulaPlugin.onEnable`. Until D1 lands, the plugin's own "non-Folia → OBSERVE shadow"
   story is fiction on Paper.
2. **The non-Folia shadow executor is a no-op.** `wireShadowExecutor` (the `else` branch at
   `NebulaPlugin.java:456`) installs a `TickExecutor` that only logs `dirtyTasks.size()`. The real DAG
   drive (`executeOwnedDag` → `MicroStepScheduler` → CAS stores) is wired ONLY in the `if (isFolia)`
   branch (`wireRegionAwareExecutor`). So on Paper today there is nothing to read back.

### Tasks — ordered so each is ONE verifiable Ralph cycle

Do these top-to-bottom; each is scoped to a single commit. D1–D2 are the pure-code enablers (no live
server, fast to verify) and MUST land before the ⚡ live-diff tasks. A ⚡ marks a task that touches the
live tick pipeline and therefore requires a live server run, not just a green unit test.

- [ ] D1. **Fix the runtime detection so Paper is correctly treated as non-Folia.** In
      `NebulaPlugin.onEnable`, switch the `isFolia` decision from `FoliaRuntimeDetector.isFoliaRuntime()`
      (API-class probe — true on Paper) to `FoliaRuntimeDetector.isFoliaServer(ctxLoader)` (server-internal
      `RegionizedServer` probe — false on Paper). Keep the API-class check only where the code genuinely
      needs the *API* present (it always is, since we compile against it). Add a unit test that proves the
      two probes disagree for a classloader that has the API class but not the server class (simulating
      Paper). Pure `nebula-plugin` / `nebula-folia-adapter`; no live server. This is the prerequisite for
      D2–D5 — without it Paper runs the Folia path and D2's shadow executor is never reached.
- [x] D2. **DONE (this cycle).** `wireShadowExecutor` now installs `InlineShadowTickExecutor` (nebula-folia-adapter)
      whose `OwnedDagRunner` is the same `executeOwnedDag` body the Folia path uses, runs the whole dirty
      batch inline on the calling (main) thread with NO `RegionScheduler` hop, and wires the SAME task
      resolver + a non-Folia begin/end lifecycle driver so `endTick` actually drains. Unit-tested
      (`InlineShadowTickExecutorTest`, 4 cases: batch reaches runner once, unresolvable world drops,
      empty/null no-op). Build green; Folia boot unregressed (else-branch correctly NOT taken). Live Paper
      proof is D4. Original spec below:
- [ ] D2. **Make the non-Folia shadow executor actually drive the DAG.** Replace `wireShadowExecutor`'s
      log-only `TickExecutor` with one that runs the real cycle on the (Paper) main thread:
      `executeOwnedDag(world, dirtyTasks)` — syncFromNms → `MicroStepScheduler` → syncToNms — the same body
      the Folia region executor uses, minus the region dispatch (on Paper every chunk is owned by the main
      thread, so no `RegionScheduler.execute` hop is needed; call `executeOwnedDag` inline). Guard the NMS
      writes behind the existing observe/writeback flags. Unit-test the wiring with a fake
      `RedstoneTickHook.TickExecutor` invocation to confirm `executeOwnedDag` is reached with the dirty
      tasks. (Live proof is D3/D4.) After this, a Paper server has a live shadow DAG whose CAS store holds
      Nebula's computed power.
- [x] D3. **DONE (fe20f35, this cycle).** `/nebula diff` added. Pure `PaperDiffReport` (nebula-replay):
      `PositionDiff(pos, nebula, paper)` + `matched()`/`allMatched()` (empty report is NOT a pass) +
      `summaryLine()` "matched X / total Y" + `mismatchLines()` "<WorldPos> nebula=N paper=M", unit-tested
      7 ways (`PaperDiffReportTest`). `NebulaPlugin.emitPaperDiff()` reads `nebula=getPowerLevel(pos)` and
      `paper=readNmsPower(pos)` INLINE on the main thread (no RegionScheduler hop — single-region host owns
      every chunk; read-only `readNmsPower` avoids the divergence tautology). `handleDiff` self-guards to
      `isFoliaServer()==false`. LIVE on Folia: the command REFUSED cleanly (no NPE) and the Folia path was
      unregressed; the actual read-back is D4 (first live Paper run). Original spec: For every position in
      `componentMap`, read (a) Nebula's CAS-computed power via `redstoneState.getPowerLevel(pos)` and (b)
      Paper's authoritative block power via a main-thread NMS/Bukkit read (legal on Paper — this is the
      capability Folia denies). Report each mismatch as `pos: nebula=N paper=M` and a final
      `matched X / total Y`. This is the differential probe: on a settled circuit the two must be identical.
      Keep the read strictly main-thread and document that it will NPE on Folia (so the command self-guards
      to `isFoliaServer()==false`). Unit-test the compare/format logic with injected values.
- [x] ⚡ D4. **DONE — ⚡ LIVE-VERIFIED on real Paper 26.1.2 (2026-07-10).** THE FIRST SINGLE-THREAD
      ORACLE COMPARISON: Nebula's shadow DAG computed the SAME redstone power the single-thread Paper
      server did. Stood up `paper-test-server/` (mirrors `folia-test-server/`: `server.jar` =
      `paper-26.1.2-74.jar`, **no javaagent** — the Bukkit `RedstoneEventListener` fallback seeds dirty
      positions, confirmed in the log). Nebula took the correct non-Folia path ("Folia server: false",
      "inline shadow DAG executor (OBSERVE mode, non-Folia)", main-thread begin/end lifecycle driver).
      Placed the canonical `lever→15 wire→lamp` line at y=-60, `/nebula scan` → 16 components / 1 toggle
      source / 16 tracked. **Result: pre-toggle `/nebula diff` = matched 0/16 (nebula=-1, CAS not yet
      populated — correctly NOT a pass); after toggling the lever ON and settling, RedstoneWorldState
      populated 0→16 and `/nebula diff` = `matched 16 / total 16`.** The match is NON-TRIVIAL: `/nebula
      diag` showed the shadow CAS held the full decay gradient (x=1→15, x=2→14, … x=15→1, lamp), not
      zeros. OFF transition also settled to matched 16/16. Server stopped cleanly via RCON. **HONESTY
      CAVEAT for D5:** D2's executor runs the DAG INLINE on the main thread, so this proves shadow==authority
      but does NOT yet exercise the *parallel* claim — that is exactly D5.
- [x] ⚡ D5. **DONE — ⚡ LIVE-VERIFIED on real Paper 26.1.2 with the DAG worker pool ON (2026-07-10).**
      THE DECISIVE PARALLEL==SINGLE-THREAD EXPERIMENT. Booted `paper-test-server/` with
      `NEBULA_DAG_PARALLEL=true` (start.sh now honours that env → `-Dnebula.dag.parallel=true`); the plugin
      logged "Folia server: false", "DAG PARALLEL ENABLED … 12 worker threads", and the non-Folia inline
      shadow path. Placed the canonical `lever→15 wire→lamp` line at y=-60,z=0, `/nebula scan`. **Pre-toggle
      `/nebula diff` = matched 16/32 with nebula=-1 (CAS unpopulated — correctly NOT a pass). After toggling
      the lever ON and settling, `/nebula diff` = `matched 32 / total 32 — shadow matches the single-thread
      authority exactly`.** NON-TRIVIAL: `/nebula diag` (CASCADE-DIAG) showed the parallel-wrapped runner
      computed the full decay gradient (x=1→15, x=2→14, … x=15→1, source-seed 15), not zeros. OFF transition
      also settled to matched. THEN scaled to a **second independent circuit** in a different chunk (z=32):
      48 components / 3 toggle sources; toggling both levers together (cross-chunk concurrent work) →
      `/nebula diff` = **matched 48 / total 48** on both the ON and OFF transitions. Whole run: 0 exceptions,
      0 CAS-commit failures, 0 degrade-to-serial events (the only log hit for "degrade" is the boot banner's
      own explanatory text). Server stopped cleanly via RCON. This is the FIRST direct evidence that Nebula's
      DAG running on a real multi-worker pool produces the SAME redstone state a single-thread MC server
      does — the claim the whole project rests on. **HONEST LIMITS:** (a) a single straight wire line mostly
      runs degraded-serial (wire tasks WAW-serialize on `REGION_*` globals into separate layers), so the
      genuine concurrency here comes from the two independent circuits, not within one line — a wider fan-out
      (many small global-free components, or many circuits) would exercise the pool harder; (b) "invariant to
      worker count" was verified at 12 workers vs. D4's inline-serial run (both matched==total), not swept
      across N∈{2,4,8}; a worker-count sweep would tighten the "invariant to N" claim. (c) start.sh lives in
      the untracked `paper-test-server/` runtime dir (like folia-test-server), so it is NOT committed; the
      NEBULA_DAG_PARALLEL env toggle it now reads is recorded here as the repro recipe.

      *Original spec:* Turn on real multi-threaded DAG execution and re-diff. D2 runs the DAG inline on the
      main thread — correct but serial, so it does not yet exercise the *parallel* claim. Configure the DAG
      executor to run layers across a real worker pool (the `nebula-core` executor, not inline), re-run the
      D4 circuit + toggle + `/nebula diff`, and confirm **still `matched == total`**. THIS is the decisive
      experiment for the project's core claim: identical result whether the DAG ran serial or parallel,
      both matching the single-thread server. Then scale up (multiple independent circuits in different
      chunks) so the pool genuinely runs tasks concurrently, and re-confirm. Document worker count and that
      the result is invariant to it.
      - [x] **D5 slice 1 DONE (5ee5ece, 2026-07-10, ⚡ LIVE-unregressed on Folia).** The prerequisite
            *reachability* fix: `MicroStepScheduler.executeTick` now drives each topological layer through
            `TaskRunner.runLayer()` instead of a private per-task `run()` loop, so a `ParallelTaskRunner`
            (which fans a layer across an `Executor`) can finally plug into the production redstone path —
            it was dead/unreachable before. Serial default runner = byte-identical behaviour; live Folia
            toggle drove 4216 dirty cascades / 4215 `modified=1` ticks, full 15→1 CAS gradient, 0 exceptions.
            Does NOT turn parallelism on. Unit test: `layerExecutionGoesThroughRunLayerSeam`.
      - [x] **D5 slice 2 part 1 DONE (eacdbe0, 2026-07-10, ⚡ LIVE-unregressed on Folia).** The audited
            degradation blocker: `RedstoneTaskFactory.create` built tasks with the 4-arg `TaskNode` ctor
            (`parallelSafe=false`), and `ParallelTaskRunner.runLayer` degrades any layer with a
            non-`parallelSafe` task to serial — so a pool would silently no-op. The factory now marks tasks
            `parallelSafe = rw.randomUsage().isEmpty()` (conservative + honest: all other same-layer conflicts
            — blocks, BEs, entities, AND globals — are excluded by DAG construction, and writes are
            snapshot-buffered + CAS-committed serially after `runLayer`; the only untracked shared resource is
            a shared `Random`, which no redstone RWSet declares). Flag is provably inert for the serial live
            path (only `ParallelTaskRunner` reads it; no hasher/capture does). Tests:
            `everyComponentTypeProducesAParallelSafeTask`, `parallelSafeMarkingDoesNotAlterRwSetOrTaskIdentity`.
      - [x] **D5 slice 2 part 2 DONE (this cycle, ⚡ LIVE-VERIFIED parallel path on Folia).** The literal
            "just wrap `redstoneRunner`" instruction had a FALSE PREMISE: `MicroStepScheduler` gated BOTH
            change-detection (`world()`) AND the CAS commit lifecycle (`commitLayer()`/`resetLayer()`) on
            `runner instanceof RedstoneTaskRunner`. Wrapping the runner in a `ParallelTaskRunner` (what the
            pool needs) makes both `instanceof` checks FAIL → `world==null` (no microstep cascade) and
            `commitLayer` never called (no CAS writes) — the parallel path would silently compute and write
            NOTHING. Fix = a reachability seam: added `TaskRunner.unwrap()` (default returns `this`),
            overridden by `ParallelTaskRunner` to expose its delegate; `MicroStepScheduler` now resolves the
            committing `RedstoneTaskRunner` via `runner.unwrap()`. Serial path byte-unchanged (bare runner
            unwraps to itself). THEN wired the pool: `NebulaPlugin.onEnable` wraps `redstoneRunner` in a
            `ParallelTaskRunner` over a daemon `newFixedThreadPool(max(2,cores))` gated behind
            `-Dnebula.dag.parallel` (default OFF, field null unless set, `shutdownNow` in `onDisable`); the
            composite runner keeps the bare `redstoneRunner` for its `LayerCommitting` contract. Unit:
            `MicroStepSchedulerTest.parallelWrappedRunnerStillCommitsAndCascadesLikeSerial` proves a
            4-thread-pool-wrapped run reproduces the serial run's whole-line 15→(15-k) decay gradient AND
            commits cleanly. ⚡ LIVE on Folia 26.1.2 with `-Dnebula.dag.parallel=true` (12 workers): a
            lever→15-wire→lamp toggle drove 75 real multi-microstep cascades through the parallel-wrapped
            runner (e.g. `seedTasks=1 microsteps=3 modified=4`, CAS `15→0` writes down the line), 0 CAS-commit
            failures, 0 exceptions, 0 degrade warnings — `unwrap()` correctly located the committer under the
            wrapper. **HONEST LIMITS (unchanged from part 1, still open):** (a) this proves the parallel path
            RUNS and COMMITS correctly live, NOT yet that it beats serial or that a single wire line achieves
            genuine concurrency — wire/repeater/comparator/torch WAW-serialize on `REGION_*` globals into
            separate layers, so a straight line mostly runs degraded-serial; (b) the D4-style `/nebula diff`
            "matched==total with parallel ON" confirmation on **Paper** (the single-thread oracle) is still
            unrun — Folia is itself multi-threaded, so this Folia run is not the oracle comparison D5's
            definition-of-done names. **D5 next slice:** run `/nebula diff` on Paper with `-Dnebula.dag.parallel`,
            confirm `matched==total` invariant to worker count, then scale to multiple independent circuits in
            different chunks so the pool genuinely runs tasks concurrently.

**Definition of done for B9 — ✅ MET (2026-07-10).** A Paper run placed canonical circuits, toggled them,
and `/nebula diff` reported zero mismatches against the single-thread authoritative state **with Nebula's
DAG executing on a 12-worker pool** (`-Dnebula.dag.parallel=true`) — matched 32/32 on one circuit and
48/48 across two independent cross-chunk circuits, on both ON and OFF transitions. This is the first direct
evidence for "multi-thread Nebula == single-thread MC," the claim the whole project rests on. See the D5
record above for the full method and its honest limits (single-line concurrency is degraded-serial; the
concurrency proof rests on the independent second circuit; worker-count invariance shown 12-vs-inline, not
swept N∈{2,4,8}).

**Caveats to stay honest about:** (a) Paper single-region means the *server* is single-threaded; the
parallelism under test is Nebula's DAG pool, which is the right unit but not a full multi-region proof —
the multi-region story still needs Folia. (b) Observe-mode timing: if Paper settles a signal before the
shadow's syncFromNms reads it, the shadow sees the settled value and trivially matches; a meaningful diff
must sample the transient or drive the toggle deterministically (reuse `LiveLoadToggleDriver` /
`DeterministicToggleSchedule`). (c) Redstone only until the entity/BE subsystems are live-wired (see B8/C).

---

**Last Updated**: 2026-07-10 (B9 D5 CLOSED — ⚡ parallel DAG == single-thread MC, LIVE on Paper. **B9 DONE.**)
**Verified this session (2026-07-10)**: ⚡ B9 D5 LIVE on real Paper 26.1.2 with the DAG worker pool ON
(`-Dnebula.dag.parallel=true`, 12 workers) — `/nebula diff` = matched 32/32 on the canonical circuit and
48/48 across two independent cross-chunk circuits, on both ON and OFF transitions; non-trivial (CASCADE-DIAG
showed the parallel-wrapped runner computed the full 15→1 decay gradient), 0 exceptions / 0 CAS-commit
failures. This closes B9: the FIRST direct evidence that Nebula's DAG on a real multi-worker pool produces
the SAME redstone state a single-thread MC server does. Honest limits (single-line = degraded-serial,
concurrency proven via the second circuit, worker-count not swept) recorded in the D5 entry above.
**Source of truth**: docs/PROJECT_STATUS.md
