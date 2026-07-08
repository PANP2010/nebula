# Nebula Quick Start Guide

**For developers picking up this project after the documentation update.**

---

## What You Need to Know

### The Situation (updated 2026-07-08)
- **Code quality**: Good. 691 unit tests pass, architecture is solid.
- **Integration status**: Core DAG execution and deterministic zero-diff capture are both VERIFIED on real Folia 26.1.2 (2026-07-08). B1/B2/B3/B6 are resolved.
- **Verified**: live circuit toggles → DAG ticks; two identical captures → byte-for-byte identical `.nrp` files; DG1 Criteria 1/2/3 all PASS at multi-region scale (each with a documented caveat — see PROJECT_STATUS.md).
- **Still unverified**: performance under sustained load, zero-diff under *live* redstone (needs a tick-deterministic input driver), and the entity subsystem on a live server.

### The Plan
1. ✅ Deploy to test server and verify B1/B2 fixes work — DONE (DAG ticks fire)
2. ✅ Correctness testing (zero-diff) — DONE (byte-identical captures)
3. Next: tick-deterministic input driver so zero-diff can be tested under live redstone, not just a static world
4. Then: wire the entity subsystem into the live tick path (DG2)

---

## Key Documents (Read These First)

1. **[PROJECT_STATUS.md](PROJECT_STATUS.md)** — Honest assessment of what works and what doesn't
2. **[INTEGRATION_TESTING_GUIDE.md](INTEGRATION_TESTING_GUIDE.md)** — Step-by-step verification procedures
3. **[DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md)** — Original roadmap (now revised)
4. **[NEBULA_BLOCKERS.md](NEBULA_BLOCKERS.md)** — Detailed blocker analysis

---

## Quick Commands

### Build
```bash
cd /home/kuli/nebula
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :nebula-plugin:shadowJar
```

Output: `~/.gradle/nebula-server-build/nebula-server/nebula-plugin/libs/nebula-plugin-0.1.0-SNAPSHOT.jar`

### Deploy
```bash
cp ~/.gradle/nebula-server-build/nebula-server/nebula-plugin/libs/nebula-plugin-0.1.0-SNAPSHOT.jar \
   folia-test-server/plugins/
```

### Start Server
```bash
cd folia-test-server
./start.sh
```

### Connect RCON
```bash
rcon -H localhost -p 25576 -P nebulatest
```

### Test Commands
```rcon
/nebula status
/nebula help
/setblock 0 72 0 minecraft:redstone_wire
/nebula scan           # REQUIRED: RCON setblock does not fire BlockPlaceEvent,
                       # so command-placed redstone is invisible until scanned
/nebula perf           # DAG tick timing + microstep percentiles (DG1 Criteria 2/3)
/nebula capture start 100
```

---

## Critical Code Locations

### B1 Fix: RedstoneTickHook Lifecycle
- **File**: `nebula-plugin/src/main/java/org/nebula/plugin/NebulaPlugin.java`
- **Lines**: 303-324 (`runAtFixedRate` alternating begin/end driver)
- **What**: GlobalRegionScheduler task that alternates beginTick/endTick calls
- **Status**: ✅ VERIFIED WORKING on real Folia 2026-07-08 (DAG ticks fire on every redstone change)

### B2 Fix: Sync Component Scan
- **File**: `nebula-plugin/src/main/java/org/nebula/plugin/NebulaPlugin.java`
- **Lines**: 232-237 (synchronous loaded-chunk scan in `onEnable`)
- **What**: Synchronous scan of loaded chunks during onEnable, plus `/nebula scan` for command-placed redstone
- **Status**: ✅ RESOLVED — components register (16 found for the test circuit via `/nebula scan`)

### B3: Agent Interception Chain
- **Files**: 
  - `nebula-agent/src/main/java/org/nebula/agent/NeighborUpdateTransformer.java`
  - `nebula-agent/src/main/java/org/nebula/agent/NeighborUpdateHooks.java`
- **What**: ASM injection into Folia's neighbor update methods
- **Status**: ✅ FIXED (world-name key mismatch) 2026-07-08; `BlockRedstoneEvent` listener confirmed firing as a stable Bukkit-API fallback. Note: the agent path is observe-only (void hook, no early RETURN) — see docs/PROJECT_STATUS.md B4

### DAG Execution Entry Point
- **File**: `nebula-plugin/src/main/java/org/nebula/plugin/NebulaPlugin.java`
- **Method**: `executeOwnedDag()` (lines 368-444)
- **What**: Three-phase execution (syncFromNms → DAG → syncToNms)
- **Status**: ✅ Runs on real Folia — 45 DAG ticks from live circuit toggles, 0 exceptions (2026-07-08)

---

## Verification Checklist

Use this to track integration testing progress:

```
Phase 1: Does It Run?
[ ] Plugin loads without errors
[ ] Agent retransform succeeds
[ ] Hook sentinel verification passes
[ ] beginTick/endTick called every tick
[ ] Redstone place/break intercepted
[ ] componentMap populated
[ ] DAG execution triggered
[ ] "DAG tick" logs appear

Phase 2: Is It Correct?
[ ] Lever → wire → lamp circuit works
[ ] Power levels propagate correctly
[ ] NMS sync works bidirectionally
[ ] Capture harness records frames
[ ] State hashes are consistent

Phase 3: Is It Fast?
[ ] MSPT measured with/without Nebula
[ ] Overhead documented
[ ] Hotspots identified
[ ] Optimization plan created
```

---

## Expected Log Output (Success Case)

### On Server Start
```
[Nebula] Nebula plugin enabling — Folia runtime: true
[Nebula] Retransformed 4 Folia redstone classes
[Nebula] NeighborUpdateHooks sentinel verified: hooks are active
[Nebula] Initial sync scan complete: 0 redstone components registered
[Nebula] Nebula wired with region-aware FoliaRegionTickExecutor (OBSERVE mode)
[Nebula] NMS bridges initialized: block, entity, blockEntity
[Nebula] RedstoneTickHook lifecycle driver registered (global tick, alternating begin/end)
```

### When Redstone Placed
```
[WorldRedstoneScanner] Scanned block at (0,72,0): REDSTONE_WIRE
[Nebula] componentMap updated: 1 components
```

### Every Tick (when redstone active)
```
[Nebula] DAG tick: 1 tasks, 1 microsteps in 2ms
```

### On Capture
```
[FoliaCaptureHarness] Capture started: 100 ticks
[FoliaCaptureHarness] Capture complete: 100 frames recorded
```

---

## Common Failure Modes

### Plugin Loads But No DAG Execution
**Symptoms**: Redstone placed but no "DAG tick" logs  
**Diagnosis**: B1 not working — lifecycle never triggered  
**Fix**: Add logging to RedstoneTickHook.beginTick/endTick, verify they're called  
**See**: [INTEGRATION_TESTING_GUIDE.md](INTEGRATION_TESTING_GUIDE.md) Test 2

### DAG Executes But 0 Tasks
**Symptoms**: "DAG tick: 0 tasks" in logs  
**Diagnosis**: B2 not working — componentMap empty  
**Fix**: Check WorldRedstoneScanner, verify sync scan completes  
**See**: [INTEGRATION_TESTING_GUIDE.md](INTEGRATION_TESTING_GUIDE.md) Test 4

### Agent Verification Fails
**Symptoms**: "NEIGHBOR UPDATE HOOKS ARE NOT ACTIVE!" on startup  
**Diagnosis**: B3 not working — agent injection failed  
**Fix**: Verify `-javaagent:nebula-agent.jar` in start script, check Java 25  
**See**: [INTEGRATION_TESTING_GUIDE.md](INTEGRATION_TESTING_GUIDE.md) Test 1

---

## Architecture Recap

### Three-Phase Execution Model

```
┌─────────────────────────────────────────────────────────┐
│  Phase 1: syncFromNms                                   │
│  ├─ For each dirty position                             │
│  ├─ Read block state from world                         │
│  └─ Commit to CAS store (RedstoneWorldState)            │
├─────────────────────────────────────────────────────────┤
│  Phase 2: DAG Execution                                 │
│  ├─ MicroStepScheduler.executeTick()                    │
│  ├─ Expand initial tasks to microsteps                  │
│  ├─ Generate downstream tasks                           │
│  └─ Execute in topological order                        │
├─────────────────────────────────────────────────────────┤
│  Phase 3: syncToNms                                     │
│  ├─ For each modified position                          │
│  ├─ Read power level from CAS store                     │
│  └─ Write to real world via setBlockData()              │
└─────────────────────────────────────────────────────────┘
```

### Lifecycle Flow

```
Folia Server Start
└─> NebulaPlugin.onEnable()
    ├─> Initialize CAS stores
    ├─> Create NMS bridges
    ├─> Wire FoliaRegionTickExecutor
    ├─> Register GlobalRegionScheduler tasks
    │   ├─> Tick N (odd): beginTick("nebula-global")
    │   └─> Tick N+1 (even): endTick("nebula-global", "world")
    └─> Register chunk/block event listeners

During Server Runtime:
├─> Region Thread: Block update occurs
│   └─> NeighborUpdateHooks.onNeighborUpdate() [via agent]
│       └─> RedstoneTickHook.recordUpdate(pos)
│
└─> Global Tick Thread: endTick() called
    ├─> Collect dirty positions
    ├─> Call FoliaRegionTickExecutor.executeTasks()
    └─> For each region:
        └─> RegionScheduler.execute() → executeOwnedDag()
            ├─> Phase 1: syncFromNms
            ├─> Phase 2: DAG execution
            └─> Phase 3: syncToNms
```

---

## Next Steps

### Immediate (Today)
1. Read [PROJECT_STATUS.md](PROJECT_STATUS.md) for full context
2. Read [INTEGRATION_TESTING_GUIDE.md](INTEGRATION_TESTING_GUIDE.md) Test 1-4
3. Deploy to test server and run through Test 1
4. Verify B1/B2 fixes work

### Short-Term (This Week)
1. Complete all 8 integration tests
2. Debug and fix any failures
3. Get first successful "DAG tick" with real redstone
4. Document actual behavior vs. expected

### Medium-Term (Next 2 Weeks)
1. Zero-diff testing with simple circuits
2. Measure MSPT impact
3. Identify and fix performance bottlenecks
4. Expand test coverage

### Long-Term (Next Month)
1. Entity subsystem integration
2. DG2 acceptance testing
3. Performance optimization
4. Production readiness

---

## Resources

### Test Server Access
- Host: `kuli@192.168.31.110`
- Project: `/home/kuli/nebula`
- Server: `/home/kuli/nebula/folia-test-server`
- RCON: `localhost:25576` (password: `nebulatest`)
- Server port: `25566`

### Environment
- OS: Linux 6.17.0-35-generic
- Build JDK: Java 21 (`/usr/lib/jvm/java-21-openjdk-amd64`)
- Runtime JDK: Java 25 (`/usr/lib/jvm/java-25-openjdk-amd64`)
- Gradle: 8.13 (wrapper)
- Folia: 26.1.2-8

### Key Dependencies
- Folia API: `libs/folia-api-26.1.2.build.8-stable.jar`
- ASM: 9.6 (for agent bytecode manipulation)
- Bukkit API: Provided by Folia

---

## Getting Help

### When Stuck
1. Check [INTEGRATION_TESTING_GUIDE.md](INTEGRATION_TESTING_GUIDE.md) troubleshooting section
2. Review server logs for error messages
3. Add diagnostic logging (examples in integration guide)
4. Check git history for recent changes
5. Review unit tests for expected behavior

### Useful Git Commands
```bash
# See what changed
git diff HEAD~5

# Find when something broke
git log --oneline -20

# Compare with working branch
git diff feat/vap-phase2-month4-6
```

---

## Success Metrics

**Phase 1 Success**: "DAG tick: N tasks, M microsteps in Xms" appears in logs  
**Phase 2 Success**: Lever → lamp circuit works correctly  
**Phase 3 Success**: 10k-tick capture completes with zero diff  
**Phase 4 Success**: MSPT overhead < 20% (or better: reduction > 0%)

---

**Document Version**: 1.0  
**Created**: 2026-07-08  
**For**: Continuing development after documentation update
