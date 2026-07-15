# Nebula Integration Testing Guide

> **⚠️ SUPERSEDED (2026-07-15) — dated bring-up record, not current instructions.**
> This guide covers the 2026-07-08 B1/B2/B3 blocker verification, which is closed.
> It contains machine-specific paths and credentials that no longer apply. For the
> current smoke checks see [QUICKSTART.md](QUICKSTART.md); for current status and
> evidence see [PROJECT_STATUS.md](PROJECT_STATUS.md). Kept for historical context.

**Purpose**: Bridge the gap between passing unit tests and working end-to-end functionality.

**Target Audience**: Developers verifying the B1/B2/B3 blocker fixes on a real Folia server.

---

## Prerequisites

- Folia 26.1.2 server running at `/home/kuli/nebula/folia-test-server/`
- Nebula plugin built and deployed to `plugins/` directory
- `nebula-agent.jar` in server root with `-javaagent` flag in startup script
- RCON access (port 25576, password: nebulatest)
- Server running with Java 25

---

## Test 1: Plugin Loads Successfully

### Goal
Verify that Nebula plugin loads without errors and initializes all components.

### Steps

1. Start Folia server:
```bash
cd /home/kuli/nebula/folia-test-server
./start.sh
```

2. Watch logs for these success indicators:
```
[Nebula] Nebula plugin enabling — Folia runtime: true
[Nebula] Retransformed 4 Folia redstone classes
[Nebula] NeighborUpdateHooks sentinel verified: hooks are active
[Nebula] Initial sync scan complete: N redstone components registered
[Nebula] Nebula wired with region-aware FoliaRegionTickExecutor (OBSERVE mode)
[Nebula] RedstoneTickHook lifecycle driver registered (global tick, alternating begin/end)
```

### Expected Result
✅ Plugin loads with "Folia runtime: true"  
✅ Agent retransform succeeds (4 classes)  
✅ Hook sentinel verification passes  
✅ FoliaRegionTickExecutor wired  
✅ Lifecycle driver registered  

### Failure Modes

**If hooks verification fails**:
```
║  NEIGHBOR UPDATE HOOKS ARE NOT ACTIVE!                      ║
```
→ Check that `-javaagent:nebula-agent.jar` is in startup script  
→ Check agent version matches plugin version  
→ Verify Java 25 is being used (not Java 21)

**If componentMap is empty at startup**:
```
Initial sync scan complete: 0 redstone components registered
```
→ Expected if no redstone in loaded chunks yet  
→ Will be populated when redstone is placed

---

## Test 2: RedstoneTickHook Lifecycle Active

### Goal
Verify that `beginTick()` and `endTick()` are being called every tick.

### Steps

1. Connect via RCON:
```bash
rcon -H localhost -p 25576 -P nebulatest
```

2. Check plugin status:
```
/nebula status
```

3. Watch server logs for lifecycle activity. Add temporary logging if needed:

Edit `org.nebula.folia.bridge.RedstoneTickHook.java`:
```java
public static void beginTick(String regionId) {
    LOG.info("[LIFECYCLE] beginTick called: regionId=" + regionId); // ADD THIS
    RegionState state = getOrCreateRegionState(regionId);
    state.beginTick();
}

public static void endTick(String regionId, String worldName) {
    LOG.info("[LIFECYCLE] endTick called: regionId=" + regionId + ", world=" + worldName); // ADD THIS
    // ... rest of method
}
```

Rebuild and redeploy, then check logs for:
```
[LIFECYCLE] beginTick called: regionId=nebula-global
[LIFECYCLE] endTick called: regionId=nebula-global, world=world
```

### Expected Result
✅ `beginTick` called every odd tick  
✅ `endTick` called every even tick  
✅ Alternating pattern continuous  

### Failure Modes

**No lifecycle logs appear**:
→ GlobalRegionScheduler task not registered  
→ Check that `isFolia` is true in onEnable()  
→ Verify lines 292-313 in NebulaPlugin.java execute

**Irregular timing**:
→ GlobalRegionScheduler may be out of sync with region ticks  
→ See "Fallback: ASM Injection" section below

---

## Test 3: RedstoneTickHook Receives Updates

### Goal
Verify that placing/breaking redstone causes `recordUpdate()` to be called.

### Steps

1. Place redstone dust in the world:
```rcon
/setblock 0 72 0 minecraft:redstone_wire
```

2. Watch for interception logs. Add temporary logging if needed:

Edit `org.nebula.agent.NeighborUpdateHooks.java`:
```java
public static void onNeighborUpdate(
    BlockPos pos, Block block, BlockPos fromPos, 
    ServerLevel level, int flags, int recursionLeft
) {
    String worldName = level.dimension().location().toString();
    System.out.println("[INTERCEPT] neighborUpdate: pos=" + pos + ", world=" + worldName); // ADD THIS
    
    RedstoneTickHook.recordUpdate(worldName, pos.getX(), pos.getY(), pos.getZ());
}
```

3. Break the redstone:
```rcon
/setblock 0 72 0 minecraft:air
```

4. Check server logs for:
```
[INTERCEPT] neighborUpdate: pos=BlockPos{x=0, y=72, z=0}, world=minecraft:overworld
```

### Expected Result
✅ Each redstone place/break triggers multiple `neighborUpdate` calls  
✅ `RedstoneTickHook.recordUpdate()` is called  
✅ `dirtyPositions` grows (visible in next test)

### Failure Modes

**No interception logs**:
→ Agent bytecode injection did not work  
→ Check that classes were retransformed (Test 1)  
→ Verify `hooksActive` sentinel is true

**Interception works but recordUpdate not called**:
→ Check that `RedstoneTickHook.isActive()` returns true  
→ Verify bootstrap activation succeeded

---

## Test 4: DAG Execution Triggered

### Goal
Verify that dirty redstone positions cause DAG execution.

### Steps

1. Place a line of redstone dust:
```rcon
/fill 0 72 0 10 72 0 minecraft:redstone_wire
```

2. Place a lever to power it:
```rcon
/setblock -1 72 0 minecraft:lever[powered=true]
```

3. Watch server logs for DAG execution:
```
[Nebula] DAG tick: N tasks, M microsteps in Xms
```

4. Check componentMap registration:
```rcon
/nebula status
```

Expected output:
```
Redstone components: 12 positions tracked
Entity state: 0 entities
Block entity state: 0 tile entities
```

### Expected Result
✅ componentMap contains 11-12 entries (10 wire + lever + neighbors)  
✅ "DAG tick" log appears after lever placement  
✅ Tasks > 0, microsteps > 0  
✅ Execution time reported in milliseconds

### Failure Modes

**componentMap empty**:
→ WorldRedstoneScanner not working  
→ Check chunk load events are firing  
→ Manually trigger scan: place redstone one block at a time

**No "DAG tick" logs**:
→ `endTick()` not calling `executor.executeTasks()`  
→ Check that `dirtyTasks` is non-empty in endTick  
→ Add logging in `FoliaRegionTickExecutor.executeTasks()`

**DAG tick with 0 tasks**:
→ Task resolver returning null  
→ Check that componentMap contains the dirty positions  
→ Verify `RedstoneTaskFactory.inert()` is called

---

## Test 5: NMS Bridge Synchronization

### Goal
Verify that redstone state changes are synced from/to NMS correctly.

### Steps

1. Place powered redstone wire:
```rcon
/setblock 0 72 0 minecraft:lever[powered=true]
/setblock 1 72 0 minecraft:redstone_wire
```

2. Add logging to NmsBlockStateBridge:
```java
public long syncFromNms(World world, WorldPos pos) {
    // ... existing code ...
    int power = -1;
    if (data instanceof AnaloguePowerable ap) {
        power = ap.getPower();
        LOG.info("[BRIDGE] syncFromNms: pos=" + pos + ", power=" + power); // ADD THIS
    }
    // ... rest of method
}

public boolean syncToNms(World world, WorldPos pos, int power) {
    // ... existing code ...
    ap.setPower(clamped);
    block.setBlockData(data);
    LOG.info("[BRIDGE] syncToNms: pos=" + pos + ", power=" + clamped); // ADD THIS
    // ... rest of method
}
```

3. Watch logs during DAG execution:
```
[BRIDGE] syncFromNms: pos=WorldPos[dimensionId=0, x=1, y=72, z=0], power=15
[BRIDGE] syncToNms: pos=WorldPos[dimensionId=0, x=1, y=72, z=0], power=15
```

### Expected Result
✅ syncFromNms reads correct power level (0-15)  
✅ syncToNms writes power level back  
✅ No CAS commit failures (or <1% failure rate)  

### Failure Modes

**High CAS commit failure rate (>10%)**:
→ Version conflicts between DAG and NMS sync  
→ Indicates race condition in three-phase execution  
→ May need to adjust sync strategy

**Power levels incorrect**:
→ Bukkit API returning wrong values  
→ Check block type is actually redstone wire  
→ Verify AnaloguePowerable interface

---

## Test 6: End-to-End Redstone Propagation

### Goal
Verify that a complete redstone circuit works correctly with Nebula DAG execution.

### Steps

1. Build a simple test circuit:
```rcon
/setblock 0 72 0 minecraft:lever[powered=false]
/fill 1 72 0 10 72 0 minecraft:redstone_wire
/setblock 11 72 0 minecraft:redstone_lamp
```

2. Toggle lever ON:
```rcon
/setblock 0 72 0 minecraft:lever[powered=true]
```

3. Check lamp state:
```rcon
/execute positioned 11 72 0 run data get block ~ ~ ~
```

Expected: `lit:1b`

4. Watch DAG logs for microstep propagation:
```
[Nebula] DAG tick: 11 tasks, 10 microsteps in 5ms
```

5. Toggle lever OFF:
```rcon
/setblock 0 72 0 minecraft:lever[powered=false]
```

6. Verify lamp turns off.

### Expected Result
✅ Lever ON → lamp lights  
✅ Lever OFF → lamp darkens  
✅ DAG executes with reasonable task/microstep counts  
✅ Execution time < 50ms  

### Failure Modes

**Lamp doesn't light**:
→ Signal not propagating through DAG  
→ Check microstep count matches wire length  
→ Verify downstream task generation in RedstoneTaskGenerator

**Lamp stays lit after lever OFF**:
→ Power-off not propagating  
→ Check that power=0 is synced to NMS  
→ Verify RedstoneWorldState update logic

---

## Test 7: Capture Harness

### Goal
Verify that capture harness can record state hashes over multiple ticks.

### Steps

1. Start capture for 100 ticks:
```rcon
/nebula capture start 100
```

2. Wait for completion (~5 seconds at 20 TPS)

3. Stop and check frame count:
```rcon
/nebula capture stop
```

Expected output:
```
Capture stopped. Frames recorded: 100
```

4. Check server logs for hash computation:
```
[FoliaCaptureHarness] Capture started: 100 ticks
[WorldStateHasher] Computing state hash for world 'world'
[FoliaCaptureHarness] Capture complete: 100 frames recorded
```

### Expected Result
✅ Capture starts without errors  
✅ Frame count matches requested ticks  
✅ State hasher computes hashes for each tick  
✅ No hash computation errors  

### Failure Modes

**Capture doesn't start**:
→ FoliaCaptureHarness not initialized  
→ Check that stateHasher is non-null  
→ Verify GlobalRegionScheduler accepts the task

**Frame count is 0**:
→ Tick callback never fires  
→ Same as B1 issue (lifecycle not triggered)

**Hash computation throws exceptions**:
→ WorldStateHasher can't access world state  
→ Check thread safety (should run on global tick thread)

---

## Test 8: Performance Baseline

### Goal
Measure actual MSPT with and without Nebula to establish performance impact.

### Requires
- Spark profiler plugin or similar MSPT monitoring

### Steps

1. Install Spark on test server (if not already)

2. Run baseline without Nebula:
   - Remove Nebula from plugins/
   - Restart server
   - Run `/spark tps` for 5 minutes
   - Record average MSPT

3. Run with Nebula:
   - Add Nebula back to plugins/
   - Restart server
   - Place same redstone circuits as baseline
   - Run `/spark tps` for 5 minutes
   - Record average MSPT

4. Calculate impact:
```
Impact = (MSPT_with_nebula - MSPT_baseline) / MSPT_baseline * 100%
```

### Target
- **DG1 Acceptance**: MSPT reduction ≥ 30% (negative impact means overhead)
- **Realistic target for v0.1**: MSPT increase < 20% (optimization comes later)

### Expected Result
⚠️ Initial implementation will likely show overhead, not reduction  
⚠️ This is expected — optimization is Phase 3  
✅ Measure and document actual overhead  
✅ Identify hotspots for optimization

---

## Diagnostic Logging Additions

### Add to NebulaPlugin.java

```java
// In executeOwnedDag(), after line 373:
LOG.info(() -> "executeOwnedDag called: tasks=" + ownedTasks.size() 
    + ", componentMapSize=" + componentMap.size()
    + ", componentSnapshot=" + componentSnapshot.size());

// After line 409 (after microStepScheduler.executeTick):
LOG.info(() -> "DAG execution complete: totalTasks=" + result.totalTasks()
    + ", microSteps=" + result.microSteps()
    + ", modified=" + result.modifiedPositions().size()
    + ", failures=" + result.commitFailures().size());
```

### Add to RedstoneTickHook.java

```java
// In endTick(), before executor call:
LOG.info(() -> "RedstoneTickHook.endTick: regionId=" + regionId
    + ", world=" + worldName
    + ", dirtySize=" + state.dirtyPositions.size());
```

### Add to FoliaRegionTickExecutor.java

```java
// In executeTasks(), at method start:
LOG.info(() -> "FoliaRegionTickExecutor.executeTasks called: regionId=" + regionId
    + ", worldName=" + worldName
    + ", taskCount=" + tasks.size());
```

---

## Troubleshooting Decision Tree

```
Plugin loads?
├─ NO → Check -javaagent flag, Java 25, Folia version
└─ YES → Lifecycle active?
    ├─ NO → Check GlobalRegionScheduler registration (B1)
    └─ YES → Updates intercepted?
        ├─ NO → Check agent bytecode injection (B3)
        └─ YES → componentMap populated?
            ├─ NO → Check WorldRedstoneScanner (B2)
            └─ YES → DAG executes?
                ├─ NO → Check FoliaRegionTickExecutor wiring
                └─ YES → ✅ Success! Move to correctness/performance testing
```

---

## Success Criteria Checklist

- [ ] Plugin loads without errors
- [ ] Agent retransform succeeds (4 classes)
- [ ] Hook sentinel verification passes
- [ ] `beginTick`/`endTick` called every tick
- [ ] Redstone place/break intercepted
- [ ] `recordUpdate()` called with correct positions
- [ ] componentMap populated with placed redstone
- [ ] "DAG tick" logs appear
- [ ] Task count > 0 when redstone is dirty
- [ ] NMS bridge sync works (power levels correct)
- [ ] Redstone circuit works end-to-end (lever → lamp)
- [ ] Capture harness records 100 frames
- [ ] MSPT impact measured and documented

---

## Fallback: ASM Injection Method (if GlobalRegionScheduler fails)

If B1 fix via GlobalRegionScheduler proves unreliable (timing issues, race conditions), 
inject directly into Folia's tick loop via agent.

### Create RegionTickTransformer.java

```java
package org.nebula.agent;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class RegionTickTransformer extends ClassVisitor {
    
    public RegionTickTransformer(ClassVisitor cv) {
        super(Opcodes.ASM9, cv);
    }
    
    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                     String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        
        // Inject into RegionizedWorldData.tick() or RegionizedServer.tickRegion()
        if (name.equals("tick") && descriptor.equals("(J)V")) {
            return new MethodVisitor(Opcodes.ASM9, mv) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    // Inject: RedstoneTickHook.beginTick(regionId);
                    mv.visitLdcInsn("region-direct");
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "org/nebula/folia/bridge/RedstoneTickHook",
                        "beginTick", "(Ljava/lang/String;)V", false);
                }
                
                @Override
                public void visitInsn(int opcode) {
                    if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                        // Before return: RedstoneTickHook.endTick(regionId, worldName);
                        mv.visitLdcInsn("region-direct");
                        mv.visitLdcInsn("world"); // TODO: get actual world name
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                            "org/nebula/folia/bridge/RedstoneTickHook",
                            "endTick", "(Ljava/lang/String;Ljava/lang/String;)V", false);
                    }
                    super.visitInsn(opcode);
                }
            };
        }
        return mv;
    }
}
```

This is more invasive but guarantees lifecycle timing correctness.

---

**Document Version**: 1.0  
**Last Updated**: 2026-07-08  
**Maintainer**: Nebula Team
