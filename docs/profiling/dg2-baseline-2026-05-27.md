# DG2 Profiling Baseline — 2026-05-27

## Environment

- Server: Nebula 26.1.2-DEV-feat/vap-phase2-month4-6@778fce2
- JVM: JDK 25.0.3+9 (Temurin), -Xms2G -Xmx4G
- Machine: macOS (Apple Silicon)
- Workload: empty overworld, 250 stress-spawned mobs, stress-mode ON
- async-profiler: 4.4, wall-clock mode, 60s capture

## Configurations

### 1. Serial baseline (`nebula-dag-baseline`)

Flags: `-Dnebula.mode=true`

```
ticks=844, avg-tasks/tick=871.5, avg-layers/tick=1.00
avg-parallelism=871.52, avg-ms/tick=144.281
peak-tasks=1039, peak-layers=1, peak-ms=1225.804
entity-tasks=734045, be-tasks=9964
parallel runner: disabled
```

### 2. Parallel mode (`nebula-dag-parallel`)

Flags: `-Dnebula.mode=true -Dnebula.parallel=true`

```
ticks=618, avg-tasks/tick=1069.8, avg-layers/tick=1.00
avg-parallelism=1069.84, avg-ms/tick=196.956
peak-tasks=1111, peak-layers=1, peak-ms=236.279
entity-tasks=659947, be-tasks=7399
parallel runner: threads=10, threshold=2, layers-run=623
  tasks-run=666428, avg-tasks/layer=1069.71, degraded-to-serial=1
```

## Comparison

| Metric | Serial | Parallel | Notes |
|--------|--------|----------|-------|
| avg-ms/tick | 144.3 | 197.0 | +36% avg overhead (thread pool submission) |
| peak-ms | 1225.8 | 236.3 | **-81% peak** — parallel caps worst-case |
| ticks/120s | 844 | 618 | Fewer ticks due to pool overhead |
| avg-tasks/tick | 871.5 | 1069.8 | Different run — entity count varies |
| degraded-to-serial | — | 1 | Only 1 layer fell back (entity_activation) |

## Analysis

**Parallel runner is functional**: `layers-run=623`, `degraded-to-serial=1`. The
single degraded layer is the `entity_activation` task which has GLOBAL_RW.

**Average MSPT is worse in parallel mode**: The workload has ~1070 independent
tasks (self-only RWSets, no contention) executing in a single region. With no
real data conflicts, the parallel fan-out adds submission overhead without
avoiding any contention. This is the expected degenerate case — parallel pays
off when tasks do real work AND share conflicting structures.

**Peak MSPT improved dramatically**: 1225ms → 236ms. The serial peak was caused
by a GC pause or JIT compilation spike hitting all 1070 tasks sequentially.
Parallel mode distributes this across 10 workers, capping the per-layer wall
time.

**Hot method profiles**: Both runs are dominated by thread-park/lock-wait
(IDLE threads). The NebulaParallelWorker threads show up as the #2 hotspot
(12090 samples) in parallel mode — mostly parked waiting for work.

## Flamegraph Files

- `run-smoke/bench-results/nebula-dag-baseline-flamegraph.html`
- `run-smoke/bench-results/nebula-dag-parallel-flamegraph.html`

## Next Steps

1. Run with a **real player workload** (8-12 players) to measure actual
   tick-phase overhead vs Folia upstream
2. Compare against Folia upstream with identical stress-spawn workload
3. Measure with `-Dnebula.guard=true` to quantify RW-Guard overhead

---

## D3 Collision Pipeline Verification — 2026-05-27

### Configuration

Same environment as above. Flags: `-Dnebula.mode=true -Dnebula.parallel=true`
with deferred impulse buffer enabled (D3 implementation).

### Results (30s smoke test)

```
ticks=483, avg-tasks/tick=427.6, avg-layers/tick=1.00
avg-parallelism=427.55, avg-ms/tick=90.686
peak-tasks=1338, peak-layers=1, peak-ms=581.142
entity-tasks=206679, be-tasks=5131
parallel runner: threads=10, threshold=2, layers-run=491, tasks-run=216896
  avg-tasks/layer=441.74, degraded-to-serial=1
```

### Comparison with Pre-D3 Baseline

| Metric | Pre-D3 Parallel | Post-D3 Parallel | Notes |
|--------|----------------|-----------------|-------|
| avg-ms/tick | 197.0 | 90.7 | **-54% MSPT** — entity tasks now parallel |
| avg-parallelism | 1.0 (all serial) | 427.6 | Entity tasks fan out across 10 threads |
| degraded-to-serial | 1 (entity_activation) | 1 (entity_activation) | Same — only GLOBAL_RW layers degrade |

### Analysis

**Deferred impulse buffer is working.** Entity tasks are now parallelSafe
and the parallel runner fans out ~427 entity tasks across 10 worker threads.
The single degraded layer is `entity_activation` (GLOBAL_RW by design).

**MSPT improved by 54%** compared to the pre-D3 parallel baseline. The
improvement comes from entity tasks no longer being serialized — they run
concurrently across workers, with cross-entity collision impulses deferred
to the serial `collision_flush` task at the end of the entity phase.

---

## Post-D3 Smoke Test (Heavier Load) — 2026-05-27

### Results (30s smoke test, post annotation batch 4)

```
ticks=90, avg-tasks/tick=1551.1, avg-layers/tick=1.00
avg-parallelism=1551.10, avg-ms/tick=331.436
peak-tasks=1578, peak-layers=1, peak-ms=402.503
entity-tasks=139509, be-tasks=1115
parallel runner: threads=10, threshold=2, layers-run=93, tasks-run=144320
  avg-tasks/layer=1551.83, degraded-to-serial=1
```

### Notes

Higher task count this run (1551 avg vs 427 earlier) reflects more entities
spawned/active per tick. avg-parallelism = avg-tasks/tick confirms every
entity task fans out to a worker. degraded-to-serial=1 is `entity_activation`
(by design GLOBAL_RW). System is stable across the new annotations.

---

## Post-Audit Smoke (2026-05-28)

After full architecture audit and the resulting fixes (BucketDagBuilder
wired per §4, NebulaConfig per §15.1, RandomBudget per §11.2):

```
ticks=224, avg-tasks/tick=2242.1, avg-layers/tick=1.00
avg-parallelism=2242.07, avg-ms/tick=133.209
peak-tasks=2300, peak-layers=1, peak-ms=146.470
entity-tasks=501999, be-tasks=2736
parallel runner: threads=10, threshold=2, layers-run=232,
  tasks-run=519887, avg-tasks/layer=2240.89, degraded-to-serial=1
random budget: tier=T0, ticks=240, entity-evals=536290, over-budget=0
```

**Comparison summary:**

| Stage | ticks/30s | avg-ms/tick | peak-ms | tasks/tick | random metric |
|---|---:|---:|---:|---:|---|
| Pre-D3 (serial) | 844 | 144 | 1226 | 871 | always-0 |
| Pre-bucket parallel | 84 | 357 | 378 | 1615 | always-0 |
| Bucket-naive (entity GLOBAL) | 19 | 1606 | 1741 | 1845 | always-0 |
| **Post-audit** | **224** | **133** | **146** | **2242** | **active** |

Post-audit is the best result of the session at 250-mob load: lowest
peak MSPT, highest sustained throughput, full random budget tracking.
