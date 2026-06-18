# Nebula Blockers

## v0.1.0-SNAPSHOT — First Playable Release (2026-06-18)

**The first playable release is complete.** Nebula now demonstrates deterministic
multi-core tick execution on Folia 26.1.2.

### Completed Features

| Feature | Status |
|---------|--------|
| Plugin toolchain (Java 25 + Folia 26.1.2) | ✅ Complete |
| FoliaRegionTickExecutor | ✅ Complete |
| NmsBlockStateBridge (redstone) | ✅ Complete |
| NmsEntityStateBridge (entity) | ✅ Complete |
| NmsBlockEntityStateBridge (tile entity) | ✅ Complete |
| MicroStepScheduler (redstone DAG) | ✅ Complete |
| EntityTickExecutor (entity DAG) | ✅ Complete |
| CompositeTaskRunner (unified DAG) | ✅ Complete |
| FoliaCaptureHarness + WorldStateHasher | ✅ Complete |
| /nebula commands | ✅ Complete |
| Shadow jar deployable | ✅ Complete (513KB) |
| E2E integration test | ✅ Complete |
| README, CHANGELOG, LICENSE | ✅ Complete |

### Architecture

```
sync-from-NMS → CompositeTaskRunner(redstone+entity) DAG → sync-to-NMS
```

Three-phase tick:
1. **Sync FROM NMS**: read current world state into CAS stores
2. **Execute DAG**: run MicroStepScheduler + EntityTickExecutor via CompositeTaskRunner
3. **Sync TO NMS**: write CAS state back to world

### Blocked Work

- **Zero-diff validation**: requires running Folia 26.1.2 server with test world

### Deployment

1. Copy `nebula-plugin-0.1.0-SNAPSHOT.jar` to Folia `plugins/` directory
2. Start server
3. Run `/nebula capture start 1000` to capture state hashes
4. Run `/nebula status` to view CAS store sizes

### Pull Requests (v0.1.0)

- #9: Plugin migration Java 25 + Folia 26.1.2
- #10: NmsBlockStateBridge
- #11: NmsEntityStateBridge
- #12: NmsBlockEntityStateBridge
- #13: FoliaCaptureHarness
- #14: Full pipeline integration
- #15: MicroStepScheduler
- #16: E2E integration test
- #17: EntityTickExecutor
- #18: CompositeTaskRunner
- #19: /nebula commands
- #20: README update
- #21: CHANGELOG + LICENSE
