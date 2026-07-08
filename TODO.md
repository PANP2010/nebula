# Nebula Project - Complete TODO List

**Based on**: NEBULA_BLOCKERS.md, DEVELOPMENT_PLAN.md, PROJECT_STATUS.md  
**Current Status**: ~50% complete — DAG execution + zero-diff capture verified (2026-07-08); performance unverified  
**Realistic Timeline**: ~10-18 days of focused work remaining

---

## 🚨 CRITICAL CONTEXT (updated 2026-07-08)

**Two core milestones are now verified on a real Folia server; performance is not.**

- ✅ **Verified working**: end-to-end DAG execution (live circuit toggles → DAG ticks) and
  deterministic zero-diff capture (two identical captures → byte-for-byte identical files)
- ✅ **What works**: Architecture, unit tests (678 passing), build system, live redstone DAG, per-tick MSPT measurement (`/nebula perf`)
- ❌ **Not yet verified**: performance *under load* (B4 — per-tick measured, no load testing / baseline comparison); entity DAG not wired into
  the live tick path; multi-region coordination untested
- 🎯 **Next goal**: measure MSPT (baseline vs Nebula) and profile `executeOwnedDag` under load

> Phase 1 (make the DAG execute) and the Phase 2 zero-diff goal are DONE. The
> remaining work below starts effectively at performance measurement.

---

## PHASE 1: Make DAG Execute on Real Server (P0 - Days 1-5)

**Goal**: Get ONE successful "DAG tick: N tasks, M microsteps" log entry

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

## PHASE 2: Verify Correctness (P0 - Days 6-10)

**Goal**: Prove the DAG produces correct results (zero-diff property)

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
- [ ] **DG1 Criterion 2**: Microsteps ≤ 256 per tick
- [ ] If exceeds 256: Investigate why (circuit design or algorithm issue)
- [ ] Document microstep distribution (min/avg/max/p95/p99)

### 2.5 MSPT Baseline Measurement (Day 10)
- [ ] Stop Nebula server
- [ ] Remove Nebula plugin from plugins/
- [ ] Start vanilla Folia with same test circuits
- [ ] Install Spark plugin: `/spark tps`
- [ ] Run for 5 minutes, record average MSPT
- [ ] Restart with Nebula plugin
- [ ] Run for 5 minutes, record average MSPT
- [ ] Calculate: `(MSPT_nebula - MSPT_vanilla) / MSPT_vanilla * 100%`
- [ ] **DG1 Criterion 3**: MSPT reduction ≥ 30%
- [ ] **Realistic expectation for v0.1**: Likely OVERHEAD not reduction
- [ ] Document actual overhead for Phase 3 optimization planning

---

## PHASE 3: Optimize Performance (P1 - Days 11-17)

**Goal**: Reduce overhead, ideally achieve 30% MSPT reduction

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
- [ ] Calculate final overhead/reduction vs baseline
- [ ] **Target**: ≥30% reduction (DG1 Criterion 3)
- [ ] **Realistic**: May still have overhead in v0.1
- [ ] Document actual result, plan further optimizations if needed

### 3.6 Add Guards and Error Handling (Day 17)
- [ ] componentMap empty guard (already done, verify it works)
- [ ] DAG execution timeout (e.g., abort if > 50ms)
- [ ] Microstep overflow: graceful degradation if > 256
- [ ] CAS commit failure rate monitor (log if > 1%)
- [ ] Add configurable logging levels (INFO/DEBUG/TRACE)

---

## PHASE 4: Entity Subsystem Integration (P2 - Days 18-24)

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

## PHASE 5: Full System & Acceptance (P2 - Days 25+)

**Long-term tasks, lower priority until Phase 1-4 complete**

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
   - Risk: Too slow, can't achieve 30% MSPT reduction
   - Monitor: Phase 3 profiling will reveal this
   - Mitigation: Extensive optimization in Phase 3

4. **Java 25 Compatibility**
   - Risk: Subtle runtime differences vs compile-time
   - Monitor: Watch for strange behavior or crashes
   - Mitigation: Extensive logging, fallback to Java 21 if needed

---

## SUCCESS CRITERIA CHECKLIST

### Phase 1 Complete (Minimum Viable Integration)
- [ ] Server starts without errors
- [ ] Plugin loads successfully
- [ ] Agent retransform succeeds
- [ ] Hook sentinel verification passes
- [ ] componentMap populated with redstone
- [ ] RedstoneTickHook lifecycle active (beginTick/endTick called)
- [ ] **"DAG tick: N tasks, M microsteps" appears in logs** ⭐ CRITICAL
- [ ] Lever → wire → lamp circuit works correctly

### DG1 Complete (Redstone Subsystem)
- [ ] 10k-tick zero-diff test passes
- [ ] Microsteps ≤ 256 per tick
- [ ] MSPT reduction ≥ 30% (or document actual overhead)

### DG2 Complete (Entity Subsystem)
- [ ] 50k-tick entity zero-diff test passes
- [ ] Random over-budget rate < 1%

### DG3 Complete (Full System)
- [ ] Full system zero-diff test passes
- [ ] VAP plugin compatibility ≥ 80%
- [ ] 100-player load test at 20 TPS

---

## CURRENT BLOCKERS (As of 2026-07-08)

### P0 - Must fix before any testing
- [x] B1: RedstoneTickHook lifecycle - **CODE COMMITTED, NEEDS VERIFICATION**
- [x] B2: componentMap empty - **CODE COMMITTED, NEEDS VERIFICATION**
- [ ] B3: Agent interception chain - **NEEDS VERIFICATION**
- [ ] Phase 1.4: End-to-end DAG execution - **BLOCKED BY B1/B2/B3**

### P1 - Blocks DG1 acceptance
- [ ] B4: NMS sync overhead - **NEEDS PROFILING & OPTIMIZATION (Phase 3)**
- [ ] B5: Redstone test world - **CAN CREATE VIA RCON (Phase 2)**
- [ ] B6: Capture harness - **BLOCKED BY B1 (Phase 2)**

### P2 - Technical debt, doesn't block core functionality
- [ ] B7: Build environment portability
- [ ] B8: @NebulaRW annotation coverage

---

## ESTIMATED TIMELINE

- **Phase 1** (Days 1-5): Make DAG execute once
- **Phase 2** (Days 6-10): Verify correctness (zero-diff)
- **Phase 3** (Days 11-17): Optimize performance
- **Phase 4** (Days 18-24): Entity subsystem
- **Phase 5** (Days 25+): Full system & DG3

**Total**: ~16-24 working days (~3-5 weeks) to "actually playable"

**Critical Path**: Phase 1 → Phase 2 → Phase 3 → Phase 4 → Phase 5

---

## NEXT IMMEDIATE ACTION

🎯 **START HERE**: Phase 1.1 - Configure RCON and start server

Everything else depends on getting that first "DAG tick" log entry.

---

**Last Updated**: 2026-07-08  
**Status**: Ready for Phase 1 testing  
**Next Review**: After Phase 1 complete
