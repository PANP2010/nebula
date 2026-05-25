# Runtime RW-Set Guard

The runtime guard is the Phase -1 implementation hook for `NEBULA-PATCH-001`.

## Configuration

`config/nebula.yml` contains the operator-facing shape:

```yaml
nebula:
  rw-guard:
    enabled: false
    sampling-rate: 0.01
    mode: WARN
    violation-log: "logs/nebula-rw-violations.log"
    max-violations-per-tick: 100
    auto-patch: false
```

The code-facing equivalent is `RWGuardConfig`:

```java
RWGuard.configure(
    RWGuardConfig.enabled(RWGuardMode.WARN)
        .withViolationLog(Path.of("logs", "nebula-rw-violations.log"))
);
```

## Execution

Wrap task execution with:

```java
RWGuard.executeTaskWithTracing(tickNumber, taskNode);
```

When executing a full DAG, pass the guard runner to the core executor:

```java
DagExecutor.execute(taskGraph, new RWGuardTaskRunner(tickNumber));
```

Behavior by mode:

- `WARN`: collect violations and append JSONL reports when a log path is configured.
- `ENFORCE`: collect violations, write reports, then throw `RWGuardViolationException`.
- `TEST`: collect violations for `AnnotationPatchSuggestion` generation.

## Report Shape

Each line in the violation log is a single JSON object containing:

- `violation_id`, `timestamp`, `tick_number`
- `task_id`, `task_type`, `violation_type`
- `access_target`
- `declared_rw_set`
- `stack_trace`
- `suggested_fix`

Patch suggestions can be produced with:

```java
List<AnnotationPatchSuggestion> suggestions =
    AnnotationPatchSuggestion.fromViolations(RWGuard.getLastViolations());
```
