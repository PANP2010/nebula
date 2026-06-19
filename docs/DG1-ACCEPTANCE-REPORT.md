# DG1 Acceptance Report

## Determinism Gate 1 — Redstone Determinism

**Date**: 2026-06-18
**Version**: 0.1.0-SNAPSHOT

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

### Criterion 3: MSPT reduction ≥30% ⏳

| Metric | Target | Result |
|--------|--------|--------|
| DAG throughput (in-process) | <50ms/tick | ✅ PASS |
| MSPT reduction vs Folia baseline | ≥30% | ⏳ Requires Folia server |

**Test**: `Dg1AcceptanceTest.dg1_criterion3_msptReductionDocumented`
**Verification method**: DAG throughput measured in-process; true MSPT comparison requires live Folia server.

#### Folia Server Benchmark Procedure

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

**Conditional PASS** — Criteria 1 and 2 are fully met. Criterion 3 (MSPT) requires live Folia server measurement.

The DAG engine produces deterministic, zero-diff output across all circuit topologies at 10k+ ticks. The microstep cap is never triggered. MSPT reduction must be verified against a real Folia server with sufficient parallelism (multiple regions with independent redstone circuits).

**Recommended action**: Deploy to Folia 26.1.2 test server with redstone-dense test world, measure MSPT, and update this report with Criterion 3 results.