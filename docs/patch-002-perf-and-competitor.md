# NEBULA-PATCH-2026-001 — 变更六 & 变更七

Documentation-only patch items: the revised performance-expectation model
(附录 E) and the competitor analysis / positioning (附录 F). These have no code
surface; they are recorded here so the patch is fully tracked in-repo.

## 变更六 — Revised performance expectation model (附录 E)

The original Appendix E speedup curve was over-optimistic. Real-world parallel
systems achieve ~60–70% of the theoretical Amdahl value. Revised table:

| Cores N | Amdahl theoretical | Realistic (incl. memory wall) | Pessimistic (60% eff.) |
|--------:|-------------------:|------------------------------:|-----------------------:|
| 1       | 1.00×              | 1.00×                         | 1.00× |
| 4       | 2.78×              | 2.0–2.3×                      | 1.7× |
| 8       | 4.00×              | 2.6–3.1×                      | 2.4× |
| 16      | 5.00×              | 3.0–3.7×                      | 3.0× |
| 24      | 5.45×              | 3.3–4.0×                      | 3.3× |
| 32      | 5.71×              | 3.5–4.2×                      | 3.4× |

Notes:
- "Realistic" = excellent implementation (complete annotations, scheduling
  overhead <3%, ample memory bandwidth).
- "Pessimistic" = good implementation (some coarse serial blocks, significant
  NUMA latency).
- Expected speedup vs Folia (16 cores): 1.5–2.5×.
- Expected speedup vs vanilla (16 cores): 3.0–3.7×.

### E.3 Historical reference

| Project                       | Domain   | Theoretical | Achieved   | Efficiency |
|-------------------------------|----------|-------------|------------|-----------:|
| Linux kernel BKL removal      | OS       | 6–8×        | 4–5×       | ~60% |
| PostgreSQL parallel query     | Database | N           | 0.6–0.7N   | ~65% |
| Naughty Dog engine (PS3)      | Games    | 8×          | 5–6×       | ~70% |
| Folia (region threading)      | Minecraft| ~4× (16c)   | ~1.8×      | ~45% |

Nebula's 60–70% efficiency target sits at the upper edge of these successful
projects — ambitious but achievable.

### Relevance to current code

The `DagBuildBudget` diagnostics (build-time p50/p99/max, degraded-ticks ratio)
and the existing `FastBuildStats` / `BucketBuildStats` are the instruments that
will measure where real efficiency lands against this model. The Folia-vs-Nebula
profiling (docs/profiling/folia-vs-nebula-2026-05-28.md) is the empirical
counterpart; updating it against this revised model is gated on the external
Folia-server benchmark environment.

## 变更七 — Competitor analysis & positioning (附录 F)

### F.1 Overview

| Feature              | Vanilla   | Folia        | Luminol       | Nebula        |
|----------------------|-----------|--------------|---------------|---------------|
| Parallel model       | single    | region split | region+patch  | data-flow DAG |
| Determinism          | inherent  | none         | none          | T0: guaranteed|
| Cross-region redstone| N/A       | nondeterm.   | nondeterm.    | determ. (T0-T1)|
| Plugin compatibility | 100%      | region-adapt | region-adapt  | Level 0 transparent |
| Random determinism   | yes       | none         | none          | T0: guaranteed|
| Theoretical parallelism cap | 1× | ~4×          | ~4×           | ~5× |

### F.2 Core difference vs Folia

- **Folia = spatial divide-and-conquer:** world split into regions, each ticks
  independently; cross-region interaction is specially scheduled. Cross-region
  redstone / entity boundary crossing is order-nondeterministic.
- **Nebula = causal preservation:** parallelise only tasks with no causal data
  dependency; dependent tasks keep their order. Result: parallelism anywhere
  preserves causal consistency.

Key implication: Folia's parallelism is bounded by region granularity (too large
→ insufficient parallelism, too small → coordination overhead). Nebula's is
bounded by actual data dependencies — any two tasks touching disjoint
coordinates/entities can run in parallel regardless of spatial region.

### F.3 Folia known issues → Nebula response

| Folia known issue                       | Nebula response                  | Ref |
|-----------------------------------------|----------------------------------|-----|
| Cross-region redstone nondeterminism    | DAG edges capture cross-bucket interaction | §4.1, §5.4 |
| Entity cross-region teleport data race  | Per-tick bucket assignment by position | §6.3 |
| Lighting update duplicate entries       | DAG edge auto-dedup              | §10.2 |
| Chunk-unload state inconsistency        | MVCC snapshots ensure consistency| §10.2 |
| Static region count → load imbalance    | Task-level dynamic scheduling + work stealing | §4.4 |
| Plugins must declare region affinity    | VAP Level 0 transparent          | §13.2 |

### F.4 Nebula is not a Folia replacement

- Need "faster than vanilla" without caring about determinism → Folia is mature.
- Need "fast AND deterministic redstone" → Nebula is the only option (T0/T1).
- The two can coexist; Nebula covers the deterministic-parallel niche Folia
  doesn't address.

### Relevance to current code

The determinism work in this branch is the concrete backing for the "T0:
guaranteed" / "deterministic cross-region redstone" claims in the tables above:
the redstone replay determinism suite (10k-tick self-consistency), the entity
physics determinism suite, and the layered-RNG order-independence tests are the
evidence that Nebula's causal-preservation model actually holds. The remaining
gap — zero-diff vs vanilla rather than self-consistency — is the same external
Folia-capture blocker noted throughout DEVELOPMENT_PLAN.md.
