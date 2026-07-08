# Nebula Development Plan (Revised)

**Version**: v1.0
**Date**: 2026-06-24
**Branch**: feat/fix-folia-scheduler-v2
**Test Server**: kuli@192.168.31.110 (/home/kuli/nebula) — LAN only, no internet

---

> **Update 2026-07-08**: Phase 1 (make the DAG execute) and the Phase 2 zero-diff
> goal are DONE and verified on a real server. The status below reflects the
> original 2026-06-24 plan; see docs/PROJECT_STATUS.md for current state.

## 1. Project Status Summary

### Completion: ~50% (was ~35% at 2026-06-24)

| Dimension | Status |
|-----------|--------|
| Architecture design | Complete (1546-line whitepaper) |
| Code volume | 187 source files / 17,305 LOC main / 16,326 LOC test |
| Unit tests | 669 tests, all passing (was 659) |
| Build toolchain | Gradle multi-module, shadow jar, agent all working |
| **End-to-end DAG execution** | ✅ **Verified on real Folia (2026-07-08)** |
| **Zero-diff capture** | ✅ **Verified deterministic (2026-07-08)** |
| **Performance / MSPT** | ❌ Not measured (remaining milestone) |

### Core problem (RESOLVED as of 2026-07-08)

The integration gap that prevented core functionality from running has been closed:

1. **B1**: `RedstoneTickHook` lifecycle never called → ✅ resolved (GlobalRegionScheduler driver)
2. **B2**: `componentMap` empty when DAG runs → ✅ resolved (sync scan + `/nebula scan`)
3. **B3**: `NeighborUpdateInterceptor` chain unverified → ✅ fixed (world-name key mismatch)

The remaining core problem is **performance**: NMS sync overhead (B4) is unmeasured.

---

## 2. Roadmap

```
Phase 1: Make DAG execute on real server              (3-5 days)
  ├── 1.1 Fix RedstoneTickHook lifecycle
  ├── 1.2 Fix WorldRedstoneScanner sync scan
  ├── 1.3 Deploy & verify: place redstone → DAG runs
  └── 1.4 Verify NeighborUpdateInterceptor chain

Phase 2: Run acceptance tests                          (3-5 days)
  ├── 2.1 Create redstone test world
  ├── 2.2 DG1 10k-tick zero-diff test
  ├── 2.3 MSPT measurement
  └── 2.4 Capture Harness end-to-end verification

Phase 3: Performance fixes & technical debt            (5-7 days)
  ├── 3.1 Optimize NMS sync (batch/dedup)
  ├── 3.2 Add guards and logging
  ├── 3.3 Build environment portability
  ├── 3.4 Entity DAG integration
  └── 3.5 Expand @NebulaRW annotations

Phase 4: DG2/DG3 acceptance                           (5-7 days)
  ├── 4.1 Entity 50k-tick zero-diff
  ├── 4.2 Random budget verification
  ├── 4.3 VAP compatibility testing
  └── 4.4 Load testing

Total: ~16-24 days
```

---

## 3. Phase 1: Make DAG Execute on Real Server

### 1.1 Fix RedstoneTickHook Lifecycle

**Problem**: `RedstoneTickHook.beginTick()` / `endTick()` are never called.
`NebulaFoliaBootstrap.activate()` sets executor and resolver, but the lifecycle methods are never triggered.

**Done**: Added `GlobalRegionScheduler.runAtFixedRate` task in `NebulaPlugin.onEnable()` that calls `beginTick`/`endTick` every tick.

**To verify**:
- [ ] Deploy to Folia server, confirm `endTick` is called
- [ ] Confirm `dirtyTasks` in `endTick` is non-empty (when redstone is active)
- [ ] Confirm `TickExecutor.executeTasks()` is invoked

**Risk**: `GlobalRegionScheduler` ticks and Folia region ticks may be out of sync. `beginTick` runs on the global tick thread, but `recordUpdate` runs on region threads — potential race on `dirtyPositions`.

**Fallback**: If global scheduler doesn't work, inject via ASM into `RegionizedWorldServer.tick()`.

---

### 1.2 Fix WorldRedstoneScanner Sync Scan

**Problem**: `componentMap` is empty at `onEnable()` time. The delayed 100-tick async scan may complete after redstone ticking has already started.

**Done**: Added synchronous scan in `onEnable()` that scans loaded chunks before registering any scheduled tasks.

**To verify**:
- [ ] Confirm `componentMap` is non-empty at startup (log: "Initial sync scan complete: N components")
- [ ] Confirm `componentMap` contains correct component types

---

### 1.3 Deploy & Verify: Place Redstone → DAG Executes

**Steps**:
1. [ ] Build shadow jar and deploy to `folia-test-server/plugins/`
2. [ ] Start Folia server
3. [ ] Connect via RCON
4. [ ] Place redstone dust
5. [ ] Check logs for "DAG tick" or "executeOwnedDag" output
6. [ ] Check that `componentMap` contains the newly placed redstone

**Expected**: Log shows `DAG tick: N tasks, M microsteps in Xms`

---

### 1.4 Verify NeighborUpdateInterceptor Chain

**Problem**: Does the Agent's ASM injection actually deliver BLOCK_UPDATE events to `RedstoneTickHook.recordUpdate()`?

**Verification**:
1. [ ] While server is running, place/break redstone components via RCON `/setblock`
2. [ ] Check if `RedstoneTickHook.dirtyCount()` grows
3. [ ] If interception doesn't work, check Agent's `NeighborUpdateHooks` registration

**Debug**: Add temporary logging in `NeighborUpdateInterceptor.onNeighborUpdate()` to confirm it's called.

---

## 4. Phase 2: Run Acceptance Tests

### 2.1 Create Redstone Test World

**Requirement**: A world with standard redstone circuits for DG1 acceptance.

**Circuits**:
- [ ] 16-block redstone wire line (straight propagation)
- [ ] Redstone torch feedback circuit (torch + wire)
- [ ] Repeater delay line (4 delay settings)
- [ ] Comparator modes (compare/subtract)
- [ ] Simple 0-tick pulse generator

**Method**: Create via RCON `/setblock` and `/fill` commands, or load from structure files.

---

### 2.2 DG1 10k-Tick Zero-Diff Test

**Prerequisite**: Phase 1 complete, DAG executing normally.

**Steps**:
1. [ ] Start Capture Harness: `/nebula capture start 10000`
2. [ ] Wait 10000 ticks (~8 min @ 20 TPS)
3. [ ] Stop Capture: `/nebula capture stop`
4. [ ] Check `ReplayVerifier` output: all frame hashes match

**Acceptance**: 10,000 ticks zero diff

---

### 2.3 MSPT Measurement

**Steps**:
1. [ ] Run Folia without Nebula, measure baseline MSPT (via spark)
2. [ ] Run same workload with Nebula, measure MSPT
3. [ ] Calculate MSPT reduction

**Acceptance**: MSPT reduction ≥ 30% (DG1 Criterion 3)

---

### 2.4 Capture Harness End-to-End Verification

**Steps**:
1. [ ] Start Capture Harness
2. [ ] Verify `WorldStateHasher.hashState()` works on real Folia world
3. [ ] Verify `ReplayRecorder` correctly records frames
4. [ ] Verify `ReplayVerifier` can compare two recorded runs

---

## 5. Phase 3: Performance Fixes & Technical Debt

### 3.1 Optimize NMS Sync

**Problem**: `executeOwnedDag()` calls `syncFromNms` and `syncToNms` for every task. For large task sets, NMS sync overhead may exceed DAG execution time.

**Optimizations**:
- [ ] Deduplicate `ownedTasks` by `WorldPos` (multiple tasks may touch the same position)
- [ ] Batch NMS operations (if bridge supports it)
- [ ] Only `syncToNms` when power level actually changed

---

### 3.2 Add Guards and Logging

- [ ] `componentMap` empty guard (basic version done)
- [ ] DAG execution timeout guard
- [ ] Microstep overflow graceful degradation
- [ ] More detailed diagnostic logging (configurable)

---

### 3.3 Build Environment Portability

**Problem**: `gradle.properties` hardcodes Linux JDK paths.

**Solution**:
- [ ] Use `org.gradle.java.installations.fromEnv` environment variable
- [ ] Add `local.properties` support (gitignored)
- [ ] Document build requirements

---

### 3.4 Entity DAG Integration

**Problem**: `EntityTickExecutor` is created but not wired into the live tick path — only the redstone route of `CompositeTaskRunner` has been exercised on a real server (verified 2026-07-08). Entity physics DAG execution remains unverified live.

**Steps**:
- [ ] Verify entity task generation logic
- [ ] Test entity ticking on Folia server
- [ ] Integrate into `executeOwnedDag()` or a separate tick path

---

### 3.5 Expand @NebulaRW Annotations

**Problem**: Only 7 `@NebulaRW` annotations exist; architecture doc requires ~200.

**Priority**: Low (doesn't block core functionality, affects DG3 acceptance)

---

## 6. Phase 4: DG2/DG3 Acceptance

### 4.1 Entity 50k-Tick Zero-Diff

- [ ] Spawn entities (animals, monsters) in test world
- [ ] Run 50k-tick replay
- [ ] Verify zero diff

### 4.2 Random Budget Verification

- [ ] Verify `RandomBudget` works under real load
- [ ] Verify over-budget degradation mechanism

### 4.3 VAP Compatibility Testing

- [ ] Load mainstream plugins on Folia server
- [ ] Verify Level 0 compatibility

### 4.4 Load Testing

- [ ] Implement simulated player infrastructure
- [ ] 100-player load test
- [ ] Verify stable 20 TPS

---

## 7. Blocker Priority

| Pri | ID | Description | Status | Owner |
|-----|----|-------------|--------|-------|
| **P0** | B1 | RedstoneTickHook lifecycle not triggered | 🔧 Fixed, needs verification | - |
| **P0** | B2 | componentMap may be empty | 🔧 Fixed, needs verification | - |
| **P0** | B3 | NeighborUpdateInterceptor chain unverified | 📝 Needs verification | - |
| **P1** | B4 | executeOwnedDag NMS sync overhead | 📝 Needs optimization | - |
| **P1** | B5 | Missing redstone test world | 📝 Needs creation | - |
| **P1** | B6 | Capture Harness never run end-to-end | 📝 Needs verification | - |
| **P2** | B7 | Build environment JDK paths hardcoded | 📝 Needs fix | - |
| **P2** | B8 | @NebulaRW annotation coverage low | 📝 Needs expansion | - |

---

## 8. Acceptance Criteria Summary

| Gate | Criterion | Current Status | Target |
|------|-----------|---------------|--------|
| **DG1** | Redstone 10k-tick zero diff | ⏳ DAG executes; 40-tick zero-diff verified, 10k not yet run | TBD |
| **DG1** | Microsteps ≤ 256 | ✅ Observed ≤14 on test circuit (not stress-tested) | TBD |
| **DG1** | MSPT reduction ≥ 30% | ❌ Not measured | TBD |
| **DG2** | Entity 50k-tick zero diff | ❌ Not verified | TBD |
| **DG2** | Random over-budget rate < 1% | ✅ Unit tests pass | TBD |
| **DG3** | Full system zero diff | ❌ Not verified | TBD |
| **DG3** | Plugin Level 0 ≥ 80% | ❌ Not tested | TBD |
| **DG3** | 100 players at 20 TPS | ❌ Not tested | TBD |

---

## 9. Risk Register

| Risk | Prob | Impact | Mitigation |
|------|------|--------|------------|
| GlobalRegionScheduler out of sync with region ticks | Med | High | Fallback: ASM inject into RegionizedWorldServer.tick() |
| Agent bytecode injection incompatible with Java 25 | Low | High | Test Java 25 compat; fallback: pure Java impl |
| NMS bridge behaves differently on real world | Med | Med | Add more logging, verify incrementally |
| Redstone test world creation is complex | Low | Med | Use structure files or automated scripts |
| MSPT doesn't meet target | Med | Med | Optimize NMS sync, reduce unnecessary operations |

---

## 10. Test Server Environment

```
Host:    kuli@192.168.31.110 (LAN only, no internet access)
Project: /home/kuli/nebula
JDK:     /usr/lib/jvm/java-21-openjdk-amd64 (compile)
         /usr/lib/jvm/java-25-openjdk-amd64 (runtime)
Folia:   /home/kuli/nebula/folia-test-server/
  - server.jar: Folia 26.1.2-8
  - plugins/nebula-plugin-0.1.0-SNAPSHOT.jar: 540KB
  - nebula-agent.jar: 26KB
  - RCON: localhost:25576, password: nebulatest
  - Server port: 25566
```

**Constraints**:
- No internet access on remote machine
- All dependencies must be downloaded from the local Windows machine and transferred via SCP
- Gradle wrapper and dependencies are already cached on the remote machine
