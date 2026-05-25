# DG0 Decision Document

**Date:** TBD
**Prepared by:** TBD
**Reviewers:** TBD

---

## Inputs

### From Profiling Report

- Profiling report location: `docs/profiling-report-YYYYMMDD.md`
- Hot methods for 80% CPU: **N = TBD**
- Top method count: TBD
- Subsystem distribution:
  - REDSTONE: TBD%
  - PHYSICS: TBD%
  - AI: TBD%
  - BLOCK_ENTITY: TBD%
  - OTHER: TBD%

### From Annotation Sampling Report

- Annotation sampling report location: `docs/annotation-sampling-report-YYYYMMDD.md`
- Methods sampled: 20
- Annotation blocker ratio: **TBD%**
- Blocker categories:
  - JNI/native: TBD
  - Reflection: TBD
  - Dynamic dispatch: TBD
  - Plugin/mixin: TBD
  - Global singleton: TBD
- Clean annotations: TBD

### From Runtime Guard Validation

- Guard mode tested: TEST
- Violations detected: TBD
- False positive rate: TBD%
- False negative rate: TBD%

---

## Decision Metrics Summary

| Metric | Value | Threshold | Result |
| --- | ---: | ---: | ---: |
| Hot methods for 80% CPU (`N`) | **TBD** | <= 250 pass | TBD |
| Annotation blocker ratio | **TBD%** | < 10% pass | TBD |
| Runtime guard detection rate | **TBD%** | >= 95% | TBD |

### Metric Definitions

1. **N (Hot Methods)**: Number of methods required to cover 80% of cumulative CPU time in profiling
2. **Blocker Ratio**: Percentage of sampled methods that cannot be fully annotated
3. **Guard Detection Rate**: Percentage of intentionally seeded omissions detected by runtime guard

---

## Decision Matrix

```
                    Blockers < 10%        Blockers 10-30%       Blockers >= 30%
                +--------------------+--------------------+--------------------+
  N <= 250      |                    |                    |                    |
                |   PASS DG0         |   EXTENDED P0      |   CONTINGENCY B    |
                |   Enter Phase 0    |   (adjusted)      |   or STOP          |
                +--------------------+--------------------+--------------------+
250 < N <= 500  |                    |                    |                    |
                |   EXTENDED P0      |   EXTENDED P0     |   CONTINGENCY B    |
                |   (adjusted)      |   (adjusted)      |   or STOP          |
                +--------------------+--------------------+--------------------+
  N > 500       |                    |                    |                    |
                |   CONTINGENCY B   |   CONTINGENCY B   |   STOP             |
                |   (runtime first) |   (runtime first)  |                    |
                +--------------------+--------------------+--------------------+
```

---

## Decision Options

### Option A: DG0 Pass - Enter Phase 0

**Criteria Met:**
- [ ] N <= 250
- [ ] Blocker ratio < 10%
- [ ] Guard detection rate >= 95%

**Rationale:**
> TBD - Explain why these conditions were met.

**Next Steps:**
1. Schedule DG1 kickoff meeting
2. Begin Phase 0 staffing (3-4 engineers + 1 advisor)
3. Finalize redstone subsystem annotation scope
4. Set Phase 0 timeline: 8 months

---

### Option B: Extended Phase 0

**Criteria Met:**
- [ ] Either N in range 250-500 OR blocker ratio 10-30%

**Rationale:**
> TBD - Explain the factors causing extended timeline.

**Impact Assessment:**

| Factor | Impact | Mitigation |
| --- | --- | --- |
| Higher N | More annotation work | Allocate additional resources |
| Higher blockers | Runtime guard dependency | Accelerate runtime tracing development |
| Timeline | TBD months added | Re-prioritize Phase 0 deliverables |

**Next Steps:**
1. Develop adjusted Phase 0 plan
2. Identify which blockers are addressable
3. Plan runtime guard enhancements
4. Consider hybrid approach (annotation + runtime)

---

### Option C: Contingency B - Runtime Tracing First

**Criteria Met:**
- [ ] N > 500 OR blocker ratio >= 30%

**Rationale:**
> TBD - Explain why annotation approach is infeasible.

**Contingency B Approach:**

1. **Phase -1.5: Runtime Tracing Infrastructure**
   - Deploy bytecode instrumentation for all hot methods
   - Capture actual RW-sets at runtime
   - Build RW-set inference from traces
   - Duration: 4-6 months

2. **Phase -1.75: Trace-Based Annotation**
   - Use runtime data to guide manual annotation
   - Prioritize methods with consistent access patterns
   - Flag inconsistent methods for special handling

3. **Phase 0: Resume original plan**
   - Entry criteria: Successfully annotated >= 70% of hot methods

**Next Steps:**
1. Design runtime tracing architecture
2. Validate tracing overhead acceptable
3. Develop RW-set inference algorithms
4. Re-evaluate annotation feasibility after traces

---

### Option D: Stop Project

**Criteria Met:**
- [ ] N > 500 AND blocker ratio >= 30%
- [ ] No viable path to acceptable annotation coverage
- [ ] Contingency B not viable

**Rationale:**
> TBD - Document why all options are infeasible.

**Knowledge Capture:**
Even if stopped, document:
1. RW-set patterns discovered
2. Profiling data for future work
3. Architecture insights for related projects

---

## Final Decision

**SELECTED:** [ ] A  [ ] B  [ ] C  [ ] D

### Decision Summary

> TBD - One paragraph summarizing the decision and key factors.

### Approval

| Role | Name | Date | Signature |
| --- | --- | --- | --- |
| Project Lead | TBD | TBD | ________ |
| Architecture Owner | TBD | TBD | ________ |
| Technical Advisor | TBD | TBD | ________ |

---

## Appendices

### A: Raw Profiling Data

[TBD - Reference to full profiling report]

### B: Raw Annotation Data

[TBD - Reference to full annotation sampling report]

### C: Risk Assessment

| Risk | Probability | Impact | Mitigation |
| --- | --- | --- | --- |
| N higher than estimated | TBD% | TBD | Contingency B |
| Annotation blockers underestimated | TBD% | TBD | Runtime tracing fallback |
| Profiling infrastructure issues | TBD% | TBD | Multiple profiler options |

### D: Alternatives Considered

[TBD - Document alternatives discussed and why they were rejected]
