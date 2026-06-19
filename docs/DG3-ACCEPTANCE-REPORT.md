# DG3 Acceptance Report

## Determinism Gate 3 — Full System + VAP Compatibility

**Date**: 2026-06-18
**Version**: 0.1.0-SNAPSHOT

---

## DG3 Criteria (Arch Doc §14.4)

### Criterion 1: Full game logic replay, diff=0 (T0 mode) ⏳

| Metric | Target | Result |
|--------|--------|--------|
| Redstone + Entity + BlockEntity replay | diff=0 | ⏳ Requires Folia server |

**Status**: CompositeTaskRunner unifies all subsystem DAGs. Replay must be tested on Folia server.

### Criterion 2: Plugin Level 0 compatibility ≥80% ⏳

| Metric | Target | Result |
|--------|--------|--------|
| VAP Level 0 harness | functional | ✅ PASS |
| Plugin sandbox | functional | ✅ PASS |
| Mainstream plugins (EssentialsX, WorldGuard, etc.) | ≥80% compat | ⏳ Requires plugin testing |

**Tests**:
- `VapLevel0CompatHarnessTest`: Level 0 contract verified
- `PluginSandboxTest`: sandbox isolation verified
- `PluginCertificationTest`: certification framework verified

### Criterion 3: @ManagedState Level 1 support ≥50% ⏳

| Metric | Target | Result |
|--------|--------|--------|
| MVCC version store | functional | ✅ PASS |
| Shared state access detector | functional | ✅ PASS |
| Plugin profiler | functional | ✅ PASS |
| Adapted plugins with Level 1 | ≥50% | ⏳ Requires plugin adaptation |

**Tests**:
- `MvccVersionStoreTest`: MVCC reads/writes verified
- `SharedStateAccessDetectorTest`: shared state detection verified
- `PluginProfilerTest`: profiling framework verified

### Criterion 4: 100-player load, stable 20 TPS ⏳

| Metric | Target | Result |
|--------|--------|--------|
| Load test framework | exists | ⏳ Not implemented |
| 100-player simulation | stable 20 TPS | ⏳ Requires load testing |

**Status**: Load testing framework not yet implemented. Requires mock player generation + Folia server.

---

## Deliverables

| Deliverable | Status |
|-------------|--------|
| Full system RW annotation library | ⏳ Partial (redstone + entity + blockEntity complete) |
| Complete Nebula server | ✅ v0.1.0-SNAPSHOT |
| VAP compatibility layer (Level 0 + Level 1) | ✅ Framework exists, not wired to live plugin |
| Plugin sandbox | ✅ Verified in tests |
| DG3 decision document | ✅ This document |

---

## DG3 Decision

**NOT MET** — All four criteria require Folia server deployment and plugin testing.

The VAP Level 0/Level 1 framework exists and passes unit tests, but:
1. Not wired to live `JavaPlugin` lifecycle (interceptor unregistered)
2. Plugin compatibility requires testing against real plugin jars
3. 100-player load test requires mock player infrastructure + Folia server

**Recommended action**: Wire VAP harness to NebulaPlugin lifecycle, test against EssentialsX/WorldGuard/LuckPerms jars, implement load test framework.