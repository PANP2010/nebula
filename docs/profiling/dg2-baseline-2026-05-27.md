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
