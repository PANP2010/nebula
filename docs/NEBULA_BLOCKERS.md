# Nebula Blockers

## Active Blockers

### B1: Real Server Fork Required for Replay Verification (DG2/DG3)

**Status:** Blocking DG2 and DG3 gates  
**Since:** 2026-05-25  
**Affects:** Phase 1 Month 10-12, Phase 2 RC testing

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

### B2: Performance Benchmarking Requires JMH + Real Workload (DG2)

**Status:** Non-blocking (can proceed to Phase 2 code)  
**Since:** 2026-05-25

DG2 criterion "MSPT relative to Folia reduced ≥30%" requires:
- async-profiler integration
- JMH benchmarks for DAG build + execute hot paths
- Real server tick capture for workload replay

**Workaround:** Code correctness is verified. Performance work is parallelizable
with Phase 2 development.

---

### B3: Plugin Compatibility Testing Requires Real Plugin Jars (DG3)

**Status:** Blocking DG3 gate  
**Since:** 2026-05-25  
**Affects:** Phase 2 Month 7-8 RC testing

DG3 criterion "mainstream plugin Level 0 compatibility ≥80%" requires:
- EssentialsX, WorldGuard, LuckPerms, PlaceholderAPI jars
- A running server instance with VAP agent attached
- Integration test harness for plugin lifecycle events

**Workaround:** VAP Level 0 infrastructure (PluginTaskQueue, VapLevel,
agent-side transformers) is complete. Real plugin testing is deferred until
B1 is resolved and a server fork is available.

---

### B4: TickPipeline Intra-Layer Parallel Execution Requires Layer A NMS Annotations

**Status:** Blocking D8 (intra-layer parallelism)  
**Since:** 2026-05-26  
**Affects:** Phase 1 Month 4-6 (Layer A annotations), Phase 2 Month 1-3 (RW completeness)

The current D2/D3a/D5/D6/D7 patches assign precise RWSets to per-entity,
per-BE, and per-subsystem TaskNodes — but the underlying task action
still calls vanilla NMS code (`entity.tick()`, `ticker.tick()`, etc).
Vanilla code reads/writes entity fields, neighbouring blocks, chunk
maps, light engine state, and other shared structures that are NOT
declared in the surrounding TaskNode's RWSet.

Enabling intra-layer parallel execution under these conditions would
introduce data races on every shared mutable structure NMS touches that
isn't covered by the explicit RWSet declarations. Even the per-entity
self-write contract is violated whenever an entity damages another
entity, mounts a vehicle, pushes a hopper, or schedules a block update.

**Resolution requires:** Layer A standardisation work (architecture
§14.3 Phase 1 Month 4-6) — annotating ~130 hot-path NMS functions with
precise RWSet declarations, plus a guard layer that proves the actual
runtime accesses match the declared sets. Until then D8 must remain
single-threaded per region; cross-region parallelism (D4) is the only
safe parallelism source.

**Workaround:** D4 already gives us cross-region parallelism scaling
linearly with region count. For typical survival workloads (5-15
regions on a busy server) this captures most of the available
parallelism without correctness risk.

---

## Summary: Code Infrastructure Completeness

All Phase 0–2 *code artifacts* that can be built without a server fork are complete:

| Component | Status | Location |
|-----------|--------|----------|
| RWSet data model | ✓ | nebula-core/rw/ |
| @NebulaRW annotation | ✓ | nebula-core/annotations/ |
| BucketGrid spatial index | ✓ | nebula-core/bucket/ |
| DAG (Kahn layering + WAW arbitration) | ✓ | nebula-core/scheduler/ |
| SCC contraction (Tarjan) | ✓ | nebula-core/scheduler/ |
| MicroStepExtender | ✓ | nebula-core/scheduler/ |
| TickPipeline (unified orchestrator) | ✓ | nebula-core/scheduler/ |
| CompositeTaskGenerator | ✓ | nebula-core/scheduler/ |
| Redstone task model + annotations | ✓ | nebula-redstone/ |
| Entity physics (move/collision/response) | ✓ | nebula-entity/ |
| Block entity tasks (hopper/furnace/brewer/dropper/dispenser) | ✓ | nebula-entity/ |
| Fluid subsystem (water/lava flow + propagation) | ✓ | nebula-entity/ |
| Explosion sub-DAG (4-layer pipeline) | ✓ | nebula-entity/ |
| AI pipeline (sense→goal→pathfind→act) | ✓ | nebula-entity/ |
| Random management (budget/shadow exec/downgrade) | ✓ | nebula-core/random/ |
| VAP Level 0 plugin scheduling | ✓ | nebula-core/vap/ |
| RW-Guard validation | ✓ | nebula-guard-api/ |
| Replay recorder/player/verifier | ✓ | nebula-replay/ |
| TickPipelineReplayAdapter | ✓ | nebula-replay/ |
| JVM Agent (bytecode transform) | ✓ | nebula-agent/ |
| Folia bridge hooks | ✓ | nebula-folia-bridge/ |
| Plugin commands/monitoring | ✓ | nebula-plugin/ |

---

## Resolved

(none yet)
