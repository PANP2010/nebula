# DAG Execution Engine

`nebula-core` provides a minimal execution engine for the architecture document's layer-by-layer DAG execution model.

## Direct Execution

```java
TaskGraph graph = DagBuilder.build(tasks);
DagExecutionReport report = DagExecutor.execute(graph);
```

Execution rules:

- Tasks are grouped by stable topological layers.
- Every task in a layer completes before the next layer starts.
- Independent tasks in the same layer may be executed in parallel when an `ExecutorService` is provided.
- If any task fails, later layers are not executed and `DagExecutionException` reports the failed layer, completed tasks, and collected failures.

## Parallel Execution

```java
try (var executor = Executors.newFixedThreadPool(threads)) {
    DagExecutionReport report = DagExecutor.execute(graph, TaskRunner.DIRECT, executor);
}
```

The executor is owned by the caller. `DagExecutor` waits for all submitted tasks in each layer but does not shut the executor down.

## Guarded Execution

`nebula-guard-api` adapts the runtime RW-set guard into the executor via `RWGuardTaskRunner`:

```java
RWGuard.configure(RWGuardConfig.enabled(RWGuardMode.WARN));
DagExecutor.execute(graph, new RWGuardTaskRunner(tickNumber));
```

This is the implementation hook for `NEBULA-PATCH-001`: task execution flows through runtime trace reset, task body execution, actual access snapshot, declared RW-set comparison, JSONL report writing, and optional `ENFORCE` failure propagation.
