# DAG Build Baseline — 2026-06-09

Milestone 1/2 baseline for `docs/DEVELOPMENT_PLAN.md`. Captures DAG-build cost
across workload shapes and records two defects surfaced by the larger task
counts.

## Environment

- Host: Linux (local dev box), Java 21 (`/usr/lib/jvm/java-21-openjdk-amd64`).
- Gradle 8.13 wrapper.
- JMH 1.37, `AverageTime` mode, µs/op.
- Focused config (`-Pdagbaseline`, see `nebula-bench/build.gradle.kts`):
  2 warmup + 3 measurement iterations, 500ms each, 1 fork.
- Command:
  `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew :nebula-bench:jmh -Pdagbaseline`

Note: the focused config trades statistical rigor for speed (high error bars).
These are directional baselines, not publication-grade numbers. The shape of
the curves across task counts is the signal, not the absolute values.

## Workloads

Defined in `nebula-bench/.../DagBuildBenchmark.java`:

- `redstone` — each task reads its 6 neighbors and writes its own block, laid
  out on a square grid. Spatially local: tasks fall into spatial buckets.
- `entityOnly` — each task reads/writes one distinct entity's fields, no block
  positions. Parallel-safe, self-only.
- `conflictHeavy` — every task reads two neighbors and writes its own slot in a
  small hot-set (≤64 positions), forcing a dense conflict graph.

## Results (after Tarjan fix)

| Builder | taskCount | workload | avg µs/op |
|---|---:|---|---:|
| Bucket | 500 | redstone | 1,809 |
| Bucket | 4000 | redstone | 12,720 |
| Bucket | 8000 | redstone | 28,377 |
| Quadratic | 500 | redstone | 7,987 |
| Quadratic | 4000 | redstone | 443,761 |
| Quadratic | 8000 | redstone | 2,192,650 |
| Bucket | 500 | entityOnly | 4,161 |
| Bucket | 4000 | entityOnly | 315,889 |
| Bucket | 8000 | entityOnly | 1,229,850 |
| Quadratic | 500 | entityOnly | 3,261 |
| Quadratic | 4000 | entityOnly | 176,436 |
| Quadratic | 8000 | entityOnly | 711,734 |
| Bucket | 500 | conflictHeavy | 51,615 |
| Bucket | 4000 | conflictHeavy | 3,310,635 |
| Bucket | 8000 | conflictHeavy | 14,567,713 |
| Quadratic | 500 | conflictHeavy | 57,939 |
| Quadratic | 4000 | conflictHeavy | 3,617,332 |
| Quadratic | 8000 | conflictHeavy | 16,658,889 |

## Interpretation

### redstone — bucketing works as designed
The realistic redstone path (spatially local tasks) is where `BucketDagBuilder`
earns its keep: at 8000 tasks it builds in ~28ms vs ~2193ms for the quadratic
builder — roughly 77× faster, and near-linear in task count. No action needed;
this is the intended hot path.

### entityOnly — latent GLOBAL_BUCKET cliff
Position-less entity tasks declare no `readBlock`/`writeBlock`, so
`SpatialBucketIndex` (line 34–35) drops them all into `GLOBAL_BUCKET`.
`BucketDagBuilder` then runs an O(N²) global-vs-global scan over that bucket
(`build`, line 117–122) with no self-only short-circuit, so the bucket builder
is *slower* than the quadratic one here (1230ms vs 712ms at 8000 tasks) and
degrades super-linearly.

This is a latent edge, not a hot real-world path: the production decomposer
emits entity tasks with a block-affinity `readBlock` (for region/chunk
ownership), which routes them into spatial buckets and avoids the global scan
entirely. The synthetic `entityOnly` workload deliberately omits affinity to
isolate the cliff.

Recommended future fix (deferred — needs its own before/after benchmark and a
correctness pass on global ordering, per the blocker doc's warning about a
reverted ConcurrentHashMap cache): give `BucketDagBuilder` a self-only
short-circuit in the global-bucket scan, mirroring `detectConflicts`'
`isSelfOnlyEntityWrite()` fast path. Do NOT change global-ordering semantics
speculatively.

### conflictHeavy — inherent density
Both builders are ~equally slow (14.6s vs 16.7s at 8000) because the small
hot-set forces a near-complete conflict graph; there is little structure for
bucketing to exploit. This represents a pathological workload (thousands of
tasks contending on ≤64 positions), not a builder defect.

## Defect found and fixed: TarjanScc stack overflow

The first baseline run was missing the `redstone@4000/8000` and
`conflictHeavy@4000/8000` rows — they crashed with `StackOverflowError`.

Root cause: `TarjanScc.strongConnect` was natively recursive, recursing ~N deep
on a long dependency chain. At 4000+ connected nodes this overflowed the JVM
call stack.

Fix: converted `strongConnect` to an iterative implementation with an explicit
work stack (`Frame` holds the node + its next-neighbor index), preserving
identical semantics — deterministic neighbor ordering, low-link propagation on
"return", and the ">=2 nodes or self-loop" component-emission filter.

Regression tests added to `TarjanSccTest`:
- `deepLinearChainDoesNotOverflowStack` — 20k-node chain, expects no SCCs.
- `deepCycleIsSingleComponent` — 20k-node cycle, expects one SCC of all nodes.

After the fix all 18 benchmark rows complete and `./gradlew test` passes.

## Status vs Milestone 2

- Baseline captured (this doc). ✅
- Robustness defect (Tarjan SOE) found and fixed with tests. ✅
- No speculative micro-optimization of conflict detection — the redstone hot
  path is already ~linear, and the only slow real path (entityOnly cliff) is
  documented for a future, evidence-backed change. ✅
