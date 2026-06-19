# DG2 Acceptance Report

## Determinism Gate 2 — Entity Physics + Random Budget

**Date**: 2026-06-18
**Version**: 0.1.0-SNAPSHOT

---

## DG2 Criteria (Arch Doc §14.3)

### Criterion 1: 50000+ tick replay, zero diff ⏳

| Metric | Target | Result |
|--------|--------|--------|
| Entity physics replay (10k tick) | diff=0 | ✅ PASS |
| Entity physics replay (50k tick) | diff=0 | ⏳ Requires Folia server |

**Test**: `EntityReplayDeterminismTest.dg2ScaleEntityPhysicsReplaysDeterministically`
**Status**: 10k-tick self-consistency proven. 50k-tick requires Folia server with entity population.

### Criterion 2: Random over-budget re-execution rate <1% ✅

| Metric | Target | Result |
|--------|--------|--------|
| Well-behaved population | rate < 1% | ✅ PASS (rate = 0%) |
| Over-consuming minority | triggers T0→T1 downgrade | ✅ PASS |
| Adaptive budget absorption | absorbs after learning | ✅ PASS |

**Tests**:
- `EntityRandomBudgetTest`: well-behaved population satisfies DG2 (<1%)
- `EntityFidelityDowngradeIntegrationTest`: over-budget triggers fidelity downgrade

### Criterion 3: MSPT reduction ≥30% vs Folia ⏳

| Metric | Target | Result |
|--------|--------|--------|
| MSPT vs Folia baseline | ≥30% reduction | ⏳ Requires Folia server |

**Status**: Same as DG1 Criterion 3 — requires live Folia server measurement.

---

## Deliverables

| Deliverable | Status |
|-------------|--------|
| Entity RW annotation library | ✅ `nebula-entity/` |
| Collision detection engine | ✅ `EntityCollisionResponseAction` |
| Entity DAG engine | ✅ `EntityTickExecutor` |
| Random budget system | ✅ `RandomBudget` + `LayeredRandomSource` |
| Fidelity downgrade controller | ✅ `FidelityDowngradeController` |
| DG2 decision document | ✅ This document |

---

## DG2 Decision

**Conditional PASS** — Criterion 2 fully met. Criteria 1 and 3 require Folia server measurement.

The random budget system correctly tracks over-budget re-execution and triggers fidelity downgrade when the 1% threshold is exceeded. Entity physics replays deterministically at 10k ticks. 50k-tick and MSPT measurements require a live Folia server.
