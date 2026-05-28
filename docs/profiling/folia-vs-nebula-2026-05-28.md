# DG2 Folia vs Nebula Head-to-Head — 2026-05-28

## Methodology

Same JAR, same world, same workload, same JVM. Difference: the
`scheduler:` value in `paper-global.yml` switches between Folia paths
(`EDF`, `WORK_STEALING`) and Nebula's DAG path (`NEBULA`).

Workload (symmetric):
1. Boot server.
2. `forceload add -64 -64 64 64`.
3. `nebula stress-mode on` — gates `forEachTickingEntity` to iterate ALL
   region-local entities (vs Folia's player-gated default), so both
   schedulers actually tick the spawned mobs.
4. `nebula stress-spawn 250` — spawn 250 mobs near origin.
5. 20s warm-up, then `nebula reset`.
6. Measure 60s.
7. `/nebula bench` — `NebulaTickProbe` ring buffer (40k samples, hooked
   into `MinecraftServer.tickChildren()`) reports avg/p50/p95/p99/max.

Hardware: macOS arm64 (Apple Silicon), -Xms2G -Xmx4G.

## Final results (bucket-size=2, after fan-out + tick-gate fixes)

| Path | avg MSPT | p95 | p99 | max | samples/60s |
|---|---:|---:|---:|---:|---:|
| **Folia (EDF)** | **18.5ms** | 15.9ms | 19.1ms | 209.5ms | 1217 |
| **Nebula** | **56.0ms** | 56.0ms | 58.1ms | 89.6ms | 1089 |

### Interpretation

- **Avg MSPT: Nebula is 3.0× slower than Folia.** Architecture target
  was -30% (Nebula 30% lower than Folia); we are off by ~125 percentage
  points on the avg.
- **Peak MSPT: Nebula is 2.3× FASTER than Folia.** Folia's max sample
  is 209.5ms (a single bad tick that would be a noticeable freeze in
  game); Nebula's max is 89.6ms. **Nebula's p95 is also tighter (56ms
  vs 16ms — wait, this is the wrong direction).**
- Tail behaviour: Folia's distribution has heavy tail (max 209ms vs
  p95 16ms = 13× spread). Nebula's distribution is tight (max 90ms vs
  p95 56ms = 1.6× spread). The Nebula DAG path adds constant overhead
  but produces predictable per-tick wall time.

### Honest read

Nebula loses on average MSPT but wins on tail latency. For a real
production server the relevant signal is "no tick > 50ms" (server
freeze threshold). Under this stress workload:
- Folia: 4 ticks > 50ms in 1217 samples (0.3%) — but max 209ms is
  long enough players see lag.
- Nebula: 1089 of 1089 ticks > 50ms (100%) — the constant DAG-build
  overhead dominates.

So at 250-mob stress, Nebula is currently a regression. The constant
DAG-build cost (28ms with bucket=2) needs to drop further before
Nebula's parallelism advantage shows up.

## How we got here (improvement journey)

| Stage | avg MSPT | DAG build | Run | Notes |
|---|---:|---:|---:|---|
| Initial Nebula (default 32-chunk buckets, per-task fan-out) | 338ms | 268ms (90%) | 25ms | One bucket holds all 3000 tasks → O(N²) build |
| + Batched fan-out (one CompletableFuture per CPU chunk) | 280ms | 250ms | 18ms | -17% |
| + bucket-size=4 | 58ms | 37ms | 18ms | -79% — small buckets ≈ K<50 |
| + bucket-size=2 | **49ms** | **28ms** | **18ms** | -16% more, current best |
| + bucket-size=1 | 56ms | 33ms | 20ms | per-bucket fixed overhead dominates |

## Bench harness

`run-smoke/folia-vs-nebula.sh` runs both paths back-to-back. Edit
`config/nebula.yml` `dag.bucket-size` to retune.

## Caveats & next steps

1. **Stress mode is artificial.** Real load = a few players in
   different chunks → Folia's region scheduler can run them in parallel
   on different threads. Nebula's per-region DAG is well-suited too.
   A real "100 fake players in 4 dimensions" bench is the next
   credible workload.
2. **DAG-build cost is the remaining bottleneck.** 28ms with 3987
   tasks ≈ 7µs/task. Most of this is `RWConflictDetector.edgesFor`
   running 3 conflict checks per pair × ~500 pairs/bucket × ~30 buckets.
   Possible optimisations:
   - Cache `RWSet.hashCode()` and use it to short-circuit unequal-set
     pairs early.
   - Skip RAW/WAW symmetric checks for tasks both marked self-only
     (entity field tasks never conflict positionally with each other).
   - Pre-index by entity ID for entity-only tasks.
3. **DG2 acceptance** ("Folia -30% under real load") will not be hit
   in this branch. Need either: deeper per-conflict optimisation, or
   a workload where Folia loses ground (heavily contended single-region
   redstone/entity scenes where Nebula's fine-grained DAG can fan out
   across cores while Folia is stuck on one region thread).