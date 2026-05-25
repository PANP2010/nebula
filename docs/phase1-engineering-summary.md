# Phase -1 Engineering Summary

Generated: 2026-05-23

## Module Summary

### nebula-core (DAG + RW-Set Core)

**Files**: 18
**Tests**: 45 passing

| Class | Purpose |
|-------|---------|
| `DagBuilder` | Build DAG from task list |
| `DagExecutor` | Layer-by-layer parallel execution |
| `TarjanScc` | Detect cycles in dependency graph |
| `TaskNode` | Task with id, type, action, declared RW-set |
| `DependencyEdge` | Edge with source, target, dependency type |
| `DependencyType` | RAW, WAR, WAW enum |
| `TaskGraph` | Immutable DAG with topological layers |
| `RWSet` | Read-write set builder and conflict detection |
| `WorldPos` | Position (dimension, x, y, z) |
| `EntityField` | Entity ID + field path |
| `BlockEntityField` | Block position + field path |
| `GlobalKey` | Global state key (including wildcard) |
| `FieldPath` | Field path with prefix matching |
| `RandomInstance` | Random source enum |
| `RandomUsage` | Random usage count |

### nebula-guard-api (Runtime RW Guard)

**Files**: 13
**Tests**: 35 passing

| Class | Purpose |
|-------|---------|
| `RWGuard` | Main entry point with mode control |
| `RWGuardConfig` | Configuration (mode, sampling, logging) |
| `RWGuardMode` | WARN / ENFORCE / TEST enum |
| `ThreadLocalAccessTrace` | Per-thread access tracing |
| `ActualAccessTrace` | Immutable snapshot of actual access |
| `RWSetConsistencyChecker` | Compare declared vs actual |
| `RWSetViolation` | Violation record |
| `RWSetViolationJson` | JSON serialization |
| `AccessTarget` | Target of access (block, entity, etc.) |
| `AccessTargetType` | Enum for target types |
| `ViolationType` | Enum for violation types |
| `AnnotationPatchSuggestion` | Auto-generated fix suggestions |
| `RWGuardReportWriter` | JSONL violation logging |

### nebula-agent (Bytecode Instrumentation)

**Files**: 4
**Tests**: 1 passing

| Class | Purpose |
|-------|---------|
| `NebulaAgent` | Java agent entry point |
| `NebulaClassFileTransformer` | ClassFileTransformer implementation |
| `AccessTracingTransformer` | ASM visitor for field access |
| `TraceHooks` | Hook called on field read/write |

## Key Design Decisions

### 1. Field Access Tracing
- Uses Java agent with `ClassFileTransformer`
- ASM9 visitor pattern for GETFIELD/PUTFIELD
- Traces via `ThreadLocalAccessTrace` → `GlobalKey`
- Skips JVM/internal classes for performance

### 2. RW-Set Conflict Model
- RAW, WAW, WAR conflicts supported
- Field path prefix matching (write "nbt" conflicts with read "nbt.display")
- GlobalKey wildcard (`*`) matches all keys
- Random usage tracked separately (count per instance)

### 3. Guard Modes
- **WARN**: Log violations, continue execution
- **ENFORCE**: Throw `RWGuardViolationException`
- **TEST**: Same as WARN, for CI validation

### 4. Sampling
- Configurable sampling rate (0.0-1.0)
- Random sampling per task execution
- Max violations per tick configurable

## Test Coverage

| Module | Coverage Areas |
|--------|----------------|
| nebula-core | DAG build, topological sort, SCC detection, RW conflict |
| nebula-guard-api | Violation creation, JSON serialization, consistency check |
| nebula-agent | Bytecode transformation |

## Next Steps (Requires Folia Server)

### Week 1-2: Profiling
1. Attach async-profiler to running Folia server
2. Run 4 scenarios: redstone, survival, minigame, RPG
3. Extract top N methods for 80% CPU

### Week 3: Annotation Sampling
1. Sample 20 methods from top N
2. Manual annotation with developers
3. Validate with TEST mode

### Week 4: DG0 Decision
- N ≤ 250 → Phase 0
- 250 < N ≤ 500 → Extended Phase 0
- N > 500 → Contingency B
