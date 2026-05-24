# Nebula Blockers

## Active Blockers

### B1: Real Server Fork Required for Replay Verification (Phase 1 DG2)

**Status:** Blocking DG2 gate  
**Since:** 2026-05-25  
**Affects:** Phase 1 Month 10-12, Phase 2 entry

The `ReplayScheduler` interface requires a real game state layer that:
- Deserializes world state from replay seeds
- Applies `TickInput` (player network packets) to produce dirty task sets
- Serializes world state for SHA-256 hashing after each tick

This requires either:
1. A Folia/Paper fork with Nebula hooks injected (preferred)
2. A minimal simulated world state for unit-level replay testing

**Workaround:** All DAG correctness is verified through unit/integration tests
with synthetic task sets. The pipeline is structurally complete — only the
game-state serialization boundary is missing.

---

### B2: Performance Benchmarking Requires JMH + Real Workload (Phase 1 DG2)

**Status:** Non-blocking (can proceed to Phase 2 code)  
**Since:** 2026-05-25

DG2 criterion "MSPT relative to Folia reduced ≥30%" requires:
- async-profiler integration
- JMH benchmarks for DAG build + execute hot paths
- Real server tick capture for workload replay

**Workaround:** Code correctness is verified. Performance work is parallelizable
with Phase 2 development.

---

## Resolved

(none yet)
