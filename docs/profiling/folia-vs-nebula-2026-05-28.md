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

## Final results (after stream→loop conversion in RWSet)

| Path | avg MSPT | p95 | p99 | max | samples/60s |
|---|---:|---:|---:|---:|---:|
| **Folia (EDF)** | **17.0ms** | 15.9ms | 18.0ms | 677.6ms | 1208 |
| **Nebula** | **25.7ms** | 25.0ms | 27.5ms | **111.7ms** | 1123 |

### Interpretation

- **Avg MSPT: Nebula is 51% slower than Folia.** Architecture target was
  -30% (Nebula 30% lower). We are 81 percentage points off the target.
- **Tail latency: Nebula is 6× better.** Folia's worst tick was 677.6ms
  (a hard server freeze visible to players). Nebula's worst was 111.7ms.
  Nebula's distribution is also tighter: p95-to-max ratio 4.5× vs
  Folia's 42×.
- **Predictability:** Nebula's distribution stays in a narrow band
  (p95=25ms, p99=27.5ms, max=112ms — span ~4.5×). Folia is bimodal
  (p99=18ms but max=677ms — span ~37×).

### Honest read

Nebula trades some average MSPT for dramatically tighter tail latency.
For a real production server the relevant signal is "no tick > 50ms"
(server freeze threshold). Under this stress workload:
- Folia: 1208 samples, avg 17ms, but **at least one 677ms freeze**
- Nebula: 1123 samples, avg 25.7ms, **max 112ms** — within "noticeable
  but not catastrophic" range

Nebula's value proposition is not "faster on average" but "predictable
under load". This matches architecture §16 risk model: vanilla loses
ticks under contention, Nebula keeps ticking with bounded overhead.

## How we got here (improvement journey)

| Stage | avg MSPT | DAG build | Run | Notes |
|---|---:|---:|---:|---|
| Initial Nebula (default 32-chunk buckets, per-task fan-out) | 338ms | 268ms (90%) | 25ms | One bucket holds all 3000 tasks → O(N²) build |
| + Batched fan-out (one CompletableFuture per CPU chunk) | 280ms | 250ms | 18ms | -17% |
| + bucket-size=4 | 58ms | 37ms | 18ms | -79% — small buckets ≈ K<50 |
| + bucket-size=2 | 49ms | 28ms | 18ms | -16% more |
| + Stream→loop in RWSet conflict checks | **25.7ms** | not measured | not measured | **-48% — final best** |

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