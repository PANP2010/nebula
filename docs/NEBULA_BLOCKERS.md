# Nebula Blockers — Honest Status

## v0.1.0-SNAPSHOT — Current State (2026-06-19)

### What Works (Solid Foundation)
| Component | Status |
|-----------|--------|
| Java agent loads, hooks enabled | ✅ |
| NMS bridges (block/entity/blockEntity) | ✅ |
| MicroStepScheduler, EntityTickExecutor, CompositeTaskRunner | ✅ |
| DG1/DG2 unit tests (10k tick determinism, random budget <1%) | ✅ |
| Capture harness (1000 frames) | ✅ |
| Shadow jar (513KB) | ✅ |

### What's Broken (Blocking Release)
| Blocker | Impact | Fix Required |
|---------|--------|--------------|
| Folia scheduler incompatibility | `runTaskLater` fails on Folia → WorldRedstoneScanner never runs | Use `RegionScheduler.runDelayed()` / `runAtFixedRate()` |
| componentMap empty | No redstone components registered → no DAG tasks | Fix scheduler + WorldRedstoneScanner + BlockPlaceEvent listener |
| DAG never executes | No dirty tasks generated | Fix above |
| MSPT unmeasured | No DAG execution = no throughput data | Fix above + Folia benchmark |

### DG1/DG2/DG3 Gate Status (Arch Doc §14.2/14.3/14.4)

| Gate | Criteria | Status |
|------|----------|--------|
| **DG1** (Redstone) | 10k tick zero-diff ✅, microstep ≤256 ✅, MSPT ≥30% ❌ | **BLOCKED** |
| **DG2** (Entity+Random) | 10k tick ✅, random budget <1% ✅, MSPT ❌ | **BLOCKED** |
| **DG3** (Full+VAP) | All 4 criteria need Folia deployment | **BLOCKED** |

### Root Cause
**Folia scheduler API mismatch**: `BukkitScheduler.runTaskLater()` doesn't work on Folia 26.1.2. Need `RegionScheduler.runDelayed()` / `runAtFixedRate()` for Folia's region-threaded model.

### Next Steps (Priority Order)
1. Fix Folia scheduler integration in `WorldRedstoneScanner` + `NebulaPlugin`
2. Add `BlockPlaceEvent` listener for incremental component registration
3. Deploy to Folia test server, verify `componentMap` populated
4. Run DG1 acceptance test on Folia server
5. Measure MSPT for DG1 Criterion 3

### Test Server
- Folia 26.1.2 at `/home/kuli/nebula/folia-test-server/`
- Shadow jar: `~/.gradle/nebula-server-build/nebula-server/nebula-plugin/libs/nebula-plugin-0.1.0-SNAPSHOT.jar`
- Java 25 at `/home/kuli/jdks/jdk-25.0.3`
- RCON: localhost:25576, password: nebulatest
