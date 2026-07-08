# DG1 Acceptance Report

## Determinism Gate 1 — Redstone Determinism

**Date**: 2026-06-18
**Version**: 0.1.0-SNAPSHOT

---

> ⚠️ **UPDATE 2026-07-08 — this report's Criterion 3 target is RETIRED; live-Folia
> results now exist. [docs/PROJECT_STATUS.md](PROJECT_STATUS.md) is the source of
> truth.** The 2026-06-18 body below is kept for the record but two things have
> changed since it was written:
>
> 1. **Criterion 3 "MSPT reduction ≥30%" was formally retired (Path 2 decision,
>    commit 62704d7).** The verified architecture (commit 4a934bb) is *observe-only*
>    in both AGENT and INTERCEPT modes — the DAG is a non-authoritative shadow on
>    top of authoritative Folia, so it removes no serial work and a "reduction"
>    cannot exist by construction; `/nebula perf` can only ever report *added*
>    overhead. Criterion 3 was redefined to a **shadow-overhead budget: p99 DAG
>    tick < 3ms under a driven multi-region workload**, auto-graded by
>    `scripts/perf-harness.sh`. The "≥30% reduction" rows and the Folia-baseline
>    comparison procedure below are superseded and must not be treated as the
>    acceptance bar.
> 2. **All three criteria now have a verified PASS at multi-region scale on real
>    Folia 26.1.2 (2026-07-08)**, replacing the 2026-06-18 "Conditional PASS /
>    Criterion 3 requires Folia server" verdict — each with a documented caveat:
>    - **Criterion 1 (10k-tick zero-diff):** two independent 10,000-tick captures
>      (8 region-spaced circuits / 256 tracked positions) produced byte-for-byte
>      identical `.nrp` files (`scripts/zerodiff-harness.sh`). *Caveat:* a **static**
>      captured world — proves the capture+hash pipeline is deterministic at scale,
>      not DAG correctness under sustained *live* redstone (needs a tick-deterministic
>      input driver, still open).
>    - **Criterion 2 (microsteps ≤256):** ≤256 bound holds, and deep expansion is now
>      verified **LIVE (2026-07-09)**. A cold-toggle probe (`/nebula diag on` + a 15-wire
>      line) recorded `seedTasks=1 microsteps=14 modified=15` on the first `executeOwnedDag`
>      invocation — a single-task seed cascading the whole line in ONE `executeTick`;
>      `/nebula perf` recorded max 14. The pipeline single-task-seeds
>      (`FoliaRegionTickExecutor` dispatches `List.of(task)`, confirmed by `seedTasks=1`
>      on every diag line); the 7200-tick perf-harness "max 1" was the SETTLED-STATE case
>      (re-toggle logged `cas=0→nms=0` → 0 microsteps: Folia propagated before the
>      observe-only shadow ran). Deep expansion is thus proven BOTH in-process
>      (`MicroStepDepthTest`) AND live — the earlier "not cascading live" caveat is closed
>      with evidence.
>    - **Criterion 3 (shadow-overhead budget):** large multi-region run (16 circuits
>      / 238 components / 7097 DAG ticks) p99 **1.914ms** < 3ms → PASS.
>
> The 2026-06-18 in-process `Dg1AcceptanceTest` (Criteria 1 & 2, 10k ticks; Criterion
> 3 as a bounded-throughput proxy) still passes as part of the 691-test suite and is
> what the tables below describe; it is a unit-level determinism proof, distinct from
> the live-Folia scale verification summarized above.

---

## DG1 Criteria (Arch Doc §14.2)

### Criterion 1: 10000+ tick replay, zero diff ✅

| Metric | Target | Result |
|--------|--------|--------|
| Wire line (16 blocks) | 10k tick, diff=0 | ✅ PASS |
| Torch feedback + burnout | 10k tick, diff=0 | ✅ PASS |
| Repeater delay line | 10k tick, diff=0 | ✅ PASS |
| Liveness (state changes) | ≥1 circuit with state changes | ✅ PASS |

**Test**: `Dg1AcceptanceTest.dg1_criterion1_zeroDiffReplay`
**Verification method**: Record replay frames twice with identical inputs, verify bit-for-bit hash equality via `ReplayVerifier`.

### Criterion 2: Microstep cap 256, not triggered ✅

| Metric | Target | Result |
|--------|--------|--------|
| Wire line microsteps | ≤256 per tick | ✅ PASS |
| Torch feedback microsteps | ≤256 per tick | ✅ PASS |
| Repeater microsteps | ≤256 per tick | ✅ PASS |
| MicroStepLimitException | Never thrown | ✅ PASS |

**Test**: `Dg1AcceptanceTest.dg1_criterion2_microstepCapNotTriggered`
**Verification method**: Run 10k ticks per circuit, assert `microSteps() ≤ MAX_MICRO_STEPS` at every tick.

### Criterion 3: ~~MSPT reduction ≥30%~~ → Shadow-overhead budget (RETIRED target — see 2026-07-08 banner)

| Metric | Target | Result |
|--------|--------|--------|
| DAG throughput (in-process) | <50ms/tick | ✅ PASS |
| ~~MSPT reduction vs Folia baseline~~ | ~~≥30%~~ | ❌ **RETIRED** — impossible for an observe-only shadow (commit 62704d7) |
| Shadow-overhead budget (redefined) | p99 DAG tick < 3ms, multi-region | ✅ **PASS** — p99 1.914ms / 7097 ticks (live Folia 2026-07-08) |

**Test**: `Dg1AcceptanceTest.dg1_criterion3_msptReductionDocumented` (in-process bounded-throughput proxy only) + `scripts/perf-harness.sh` (live shadow-overhead grading).
**Verification method**: DAG throughput bounded in-process; the redefined shadow-overhead budget is auto-graded on live Folia. The original "≥30% reduction" comparison below is **superseded** — retained for historical record only.

#### Folia Server Benchmark Procedure (SUPERSEDED — retained for record)

> ⚠️ This procedure targets the retired "≥30% reduction" criterion, which presumes
> Nebula *replaces* Folia's serial redstone. It does not (observe-only shadow), so
> steps 2–4 measure a difference that is additive by construction and cannot go
> negative. The current grading is `scripts/perf-harness.sh` against the p99 < 3ms
> shadow-overhead budget. Kept below only to document what the 2026-06-18 report
> intended.

1. **Setup**: Copy `nebula-plugin-0.1.0-SNAPSHOT.jar` to Folia 26.1.2 `plugins/`
2. **Baseline**: Run standard redstone test world on vanilla Folia, measure MSPT with `/spark`
3. **Nebula**: Run same test world with Nebula plugin in INTERCEPT mode, measure MSPT
4. **Compare**: `(baseline_mspt - nebula_mspt) / baseline_mspt ≥ 0.30`
5. **Test world**: 16-block wire line + torch feedback + repeater chain, 100+ redstone components

---

## Deliverables

| Deliverable | Status |
|-------------|--------|
| Redstone RW annotation library | ✅ `nebula-core/rw/` |
| Redstone DAG engine | ✅ `MicroStepScheduler` |
| Standard test world | ⏳ Requires Folia server generation |
| Deterministic verification report | ✅ This document |
| DG1 decision document | ✅ This document |

---

## DG1 Decision

**2026-06-18 (original):** *Conditional PASS* — Criteria 1 and 2 are fully met. Criterion 3 (MSPT) requires live Folia server measurement.

The DAG engine produces deterministic, zero-diff output across all circuit topologies at 10k+ ticks. The microstep cap is never triggered. MSPT reduction must be verified against a real Folia server with sufficient parallelism (multiple regions with independent redstone circuits).

**Recommended action**: Deploy to Folia 26.1.2 test server with redstone-dense test world, measure MSPT, and update this report with Criterion 3 results.

---

**2026-07-08 (updated):** the recommended action was carried out. All three DG1
criteria now have a verified PASS at multi-region scale on real Folia 26.1.2 (see
the banner at the top of this report and [docs/PROJECT_STATUS.md](PROJECT_STATUS.md)
→ "DG1" for the authoritative status). Criterion 3's original "≥30% reduction"
target is **retired** (impossible for an observe-only shadow) and replaced by the
p99 < 3ms shadow-overhead budget, which PASSed (p99 1.914ms / 7097 ticks). Criterion
2's deep-expansion caveat is now **closed with live evidence (2026-07-09)**: a cold-toggle
`/nebula diag` probe recorded `seedTasks=1 microsteps=14 modified=15` on a 15-wire line —
a single-task seed cascading deeply in one `executeTick` (`/nebula perf` max 14). The one
remaining caveat that keeps DG1 from *unconditional* acceptance is Criterion 1's live run
using a **static** captured world (a tick-deterministic input driver for sustained-live
zero-diff is still open).