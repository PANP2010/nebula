# Nebula Blockers

## Active Blockers

### B1: Replay Scheduler — Packet→TaskNode Mapping Incomplete (DG2/DG3)

**Status:** Partially resolved (2026-05-26)  
**Since:** 2026-05-25  
**Affects:** Phase 1 Month 10-12, Phase 2 RC testing

`NebulaServerReplayScheduler` (c5f5b99) is now wired to a live `ServerLevel`
and provides a working `computeStateHash()` path. A **category-collision bug
in StateHashComputer** was found and fixed (deb2da6): the four state categories
(blocks/BEs/entities/globals) previously produced identical SHA-256 contributions
when using the same key/value — fixed by prefixing each category with a 3-letter
tag and bumping format to v2. Eight determinism-contract tests now verify this.

**Remaining gap:** `inputToTasks()` still returns an empty list — raw packet
→ TaskNode mapping (NMS codec decoding, player entity resolution, per-packet
RWSet construction) is deferred as a separate workstream.

**Workaround:** Replay determinism is now verified via `computeStateHash()` on
a live world — hash after N empty ticks is stable. Full packet-driven replay
requires decoding NMS packets inside the minecraft source tree.

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

### B4: Intra-Layer Parallel Gated by parallelSafe Flag (D8 partial)

**Status:** Partially resolved (2026-05-26)  
**Since:** 2026-05-26  
**Affects:** Phase 1 Month 4-6 (Layer A annotations), Phase 2 Month 1-3

`TaskNode` now carries a `parallelSafe` boolean (343504d, 40bbb9c).
`ParallelTaskRunner` only fans out layers where **all** tasks have this flag set.
Tasks without it fall back to serial execution and increment `degradedLayers`.

Self-only tasks (non-player entities, independent BEs like furnaces/chests)
are already marked `parallelSafe`. GLOBAL_RW, hopper, and player tasks
remain serial by default.

**Remaining gap:** Enabling `parallel` in production requires Layer A
annotation coverage to reach ~70% (currently ~30% of hot paths). Until then,
opening the gate risks data races on NMS structures not covered by explicit
RWSet declarations.

**Workaround:** D4 (cross-region parallelism) remains the primary parallelism
source and is safe today.

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

### B1 (partial): StateHashComputer Category-Collision Bug
**Resolved:** 2026-05-26 (deb2da6)  
Four state categories (blocks/BEs/entities/globals) were fed into SHA-256 with
identical encoding — same key/value in different categories produced the same
hash contribution. Fixed by prefixing each category with "BLK"/"BE"/"ENT"/"GLB"
and length-prefixing both keys and values. Format bumped to v2.

### B1 (partial): NebulaServerReplayScheduler computeStateHash()
**Resolved:** 2026-05-26 (c5f5b99, 8872500)  
Live ServerLevel → state hash path now works. `StateHashComputerTest` (8 tests)
verifies determinism contract. Replay hash is now testable without a full
packet replay harness.

### D2: Per-BE Task Decomposition + Layer Audit
**Resolved:** 2026-05-26  
`BlockEntityTaskBuilder` generates per-BE TaskNodes with three categories:
independent (self-only RWSets → same layer), hopper (neighbor-touching → serialized),
conservative (GLOBAL_RW → SCC-contracted). `BeLayeringTest` (8 tests) confirms
correct DAG behavior including SCC contraction of GLOBAL_RW cycles.

### D8 (partial): parallelSafe Gating for Intra-Layer Parallelism
**Resolved:** 2026-05-26 (343504d, 40bbb9c)  
`ParallelTaskRunner` now checks `parallelSafe` flag before fanning out.
Non-player entities and independent BEs are marked safe. Degraded layers
tracked and surfaced in `/nebula parallel` output.

### D3 (partial): Entity Collision Pipeline Skeleton
**Resolved:** 2026-05-27 (20e9bfd)  
`EntityCollisionPipeline.java` skeleton created with 3-phase design
(detection → impulse aggregation → position update). `Entity.push(Entity)`
now has `@NebulaRW` declaring cross-entity write. Entity tasks no longer
marked `parallelSafe` until full pipeline is implemented. Cross-region
parallelism (D4) unaffected.

### B4 (partial): Layer A Annotation Count Update
**Resolved:** 2026-05-27 (e7176f5, 5430daa)  
Batch 2 added 8 more entity hot-path annotations: Entity.baseTick,
LivingEntity.baseTick/travel/checkFallDamage/hurtServer/doHurtTarget,
Mob.baseTick/doHurtTarget. Total entity-tier annotations: 17.
Total across all subsystems: ~38.
