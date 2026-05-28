package org.nebula.core.scheduler;

import org.nebula.core.rw.RWSet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class DagBuilder {
    private DagBuilder() {
    }

    public static TaskGraph build(Collection<TaskNode> tasks) {
        return build(tasks, new SccContractor());
    }

    public static TaskGraph build(Collection<TaskNode> tasks, SccContractor contractor) {
        List<TaskNode> orderedTasks = tasks.stream()
            .sorted((left, right) -> left.taskId().compareTo(right.taskId()))
            .toList();

        Map<String, TaskNode> byId = new LinkedHashMap<>();
        for (TaskNode task : orderedTasks) {
            TaskNode previous = byId.put(task.taskId(), task);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate task id: " + task.taskId());
            }
        }

        // Phase 1: raw conflict detection (O(N²), fine for moderate N)
        Set<DependencyEdge> rawEdges = new LinkedHashSet<>();
        for (int i = 0; i < orderedTasks.size(); i++) {
            for (int j = i + 1; j < orderedTasks.size(); j++) {
                rawEdges.addAll(RWConflictDetector.edgesFor(orderedTasks.get(i), orderedTasks.get(j)));
            }
        }

        // Phase 2: contract SCCs so the graph is a true DAG
        SccContractor.ContractionResult contracted = contractor.contract(orderedTasks, rawEdges);

        // Phase 3: build final lookup map from contracted task list
        Map<String, TaskNode> finalById = new LinkedHashMap<>();
        for (TaskNode task : contracted.tasks()) {
            finalById.put(task.taskId(), task);
        }

        // TaskGraph constructor will Map.copyOf / Set.copyOf — pass through
        // the mutable collection directly to avoid a double copy.
        Set<DependencyEdge> edgesAsSet = contracted.edges() instanceof Set<DependencyEdge> s
            ? s : new LinkedHashSet<>(contracted.edges());
        return new TaskGraph(finalById, edgesAsSet);
    }

    /**
     * Specialised fast-path for the common entity-heavy workload pattern.
     *
     * <p>The decomposer typically emits (per tick):
     * <ul>
     *   <li>~10 subsystem tasks (world_border, tick_time, block_ticks, fluid_ticks,
     *       chunk_source, raids, block_events) with positional-free RWSets that
     *       touch global keys</li>
     *   <li>1 entity_activation (GLOBAL_RW) + N self-only entity tasks +
     *       1 collision_flush (GLOBAL_RW)</li>
     *   <li>~M block-entity tasks: independent (self-only writeBlockEntity) +
     *       hopper (writeBlockEntity neighbors) + 1 mid_tick_drain (GLOBAL_RW)</li>
     * </ul>
     *
     * <p>For this workload, edges only exist between tasks that touch globals
     * (subsystem ↔ subsystem, subsystem ↔ entity_activation, etc.) plus a few
     * BE neighbor relations. Self-only entity tasks contribute zero edges.
     *
     * <p>Algorithm:
     * <ol>
     *   <li>Partition tasks into (globalTouching, selfOnly)</li>
     *   <li>O(G²) conflict scan within globalTouching (G ≪ N typically ≤ 30)</li>
     *   <li>Self-only tasks contribute no edges among themselves</li>
     *   <li>Self-only × globalTouching: only relevant if a global task writes
     *       a positional block — none of the current decomposer tasks do, so
     *       we skip this scan</li>
     * </ol>
     *
     * <p>This drops the build cost from O(N²) to O(G²) where G is typically
     * the count of GLOBAL_RW + subsystem tasks (~15-30) regardless of N.
     */
    public static TaskGraph buildFast(Collection<TaskNode> tasks, SccContractor contractor) {
        long t0 = System.nanoTime();
        // Avoid the stream sort — most callers don't need stable ordering for
        // self-only entity tasks. Use a plain ArrayList iteration.
        List<TaskNode> orderedTasks = new ArrayList<>(tasks);
        orderedTasks.sort((l, r) -> l.taskId().compareTo(r.taskId()));
        long t1 = System.nanoTime();

        Map<String, TaskNode> byId = new LinkedHashMap<>(orderedTasks.size() * 2);
        List<TaskNode> globalTouching = new ArrayList<>();
        for (TaskNode task : orderedTasks) {
            TaskNode previous = byId.put(task.taskId(), task);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate task id: " + task.taskId());
            }
            RWSet rw = task.declaredRWSet();
            // Tightest predicate: only touchesGlobals=true tasks contribute
            // edges in the typical workload, since:
            //   - Self-only writeEntity at distinct entityIds never conflicts
            //   - Self-only writeBlockEntity at distinct positions never conflicts
            //   - Self-only writeBlock at distinct positions never conflicts (we
            //     guard isFastPathSafe to ensure no writeBlock at all)
            //   - readBlock × readBlock is a read-read non-conflict
            //   - touchesGlobals is the ONLY remaining cross-task interaction
            // Hopper-style cross-BE writes ARE detected via writtenBlockEntities
            // size > 1 (hopper writes self + facing).
            int beWriteCount = rw.writtenBlockEntities().size();
            boolean multipleBEWrites = beWriteCount > 1;
            boolean readsAnyBE = !rw.readBlockEntities().isEmpty();
            boolean readsAnyEntity = !rw.readEntityFields().isEmpty();
            if (rw.touchesGlobals() || !rw.writtenEvents().isEmpty()
                || multipleBEWrites || readsAnyBE || readsAnyEntity) {
                globalTouching.add(task);
            }
        }
        long t2 = System.nanoTime();

        // O(G²) conflict scan over the small subset that may have edges.
        // Three-way classification:
        //   - Wildcard tasks (GLOBAL_RW): write GlobalKey.ALL — ALL conflict
        //     pairwise on globals. Instead of emitting O(W²) edges (one per
        //     pair), we sort them and emit a CHAIN of W-1 sequential edges
        //     (a→b→c→...). The topological layering computes transitive
        //     closure for free, so the resulting DAG is equivalent.
        //   - Specific tasks: only conflict on overlapping non-wildcard
        //     globals; checked against each other and against wildcards
        //     using full RWConflictDetector.edgesFor.
        Set<DependencyEdge> rawEdges = new LinkedHashSet<>();
        int gtSize = globalTouching.size();
        boolean[] wildcards = new boolean[gtSize];
        List<TaskNode> wildcardTasks = new ArrayList<>(gtSize);
        List<TaskNode> specificTasks = new ArrayList<>(gtSize);
        for (int i = 0; i < gtSize; i++) {
            TaskNode t = globalTouching.get(i);
            wildcards[i] = t.declaredRWSet().writesGlobalWildcard();
            if (wildcards[i]) {
                wildcardTasks.add(t);
            } else {
                specificTasks.add(t);
            }
        }
        // Sort wildcard tasks by deterministic ordering and emit a chain.
        wildcardTasks.sort((a, b) -> DeterministicOrdering.compareTaskIds(a.taskId(), b.taskId()));
        for (int i = 0; i + 1 < wildcardTasks.size(); i++) {
            String aId = wildcardTasks.get(i).taskId();
            String bId = wildcardTasks.get(i + 1).taskId();
            rawEdges.add(new DependencyEdge(aId, bId, DependencyType.WAW));
        }
        // Specific × wildcard: each specific task conflicts with every
        // wildcard task on the wildcard's writes. Emit specific→firstWildcard
        // and lastWildcard→specific so the specific is serialised against
        // the chain. We must emit BOTH directions if the specific task both
        // reads and writes globals; otherwise pick by RAW direction.
        for (TaskNode specific : specificTasks) {
            for (TaskNode wildcard : wildcardTasks) {
                rawEdges.addAll(RWConflictDetector.edgesFor(specific, wildcard));
            }
        }
        // Specific × specific: full pairwise scan.
        for (int i = 0; i < specificTasks.size(); i++) {
            for (int j = i + 1; j < specificTasks.size(); j++) {
                rawEdges.addAll(RWConflictDetector.edgesFor(specificTasks.get(i), specificTasks.get(j)));
            }
        }
        long t3 = System.nanoTime();

        // SCC contraction — only over the conflict subgraph + isolated tasks.
        SccContractor.ContractionResult contracted;
        if (rawEdges.isEmpty()) {
            // Fast-path: no edges → no SCCs → directly use input tasks/edges.
            // Avoids Tarjan over 500+ singleton tasks.
            contracted = new SccContractor.ContractionResult(orderedTasks, List.of(), List.of());
        } else {
            contracted = contractor.contract(orderedTasks, rawEdges);
        }
        long t4 = System.nanoTime();

        Map<String, TaskNode> finalById;
        if (rawEdges.isEmpty()) {
            // No SCC mutation — reuse the byId map directly.
            finalById = byId;
        } else {
            finalById = new LinkedHashMap<>();
            for (TaskNode task : contracted.tasks()) {
                finalById.put(task.taskId(), task);
            }
        }
        Set<DependencyEdge> edgesAsSet = contracted.edges() instanceof Set<DependencyEdge> s
            ? s : new LinkedHashSet<>(contracted.edges());
        long t5 = System.nanoTime();

        FastBuildStats.record(t1 - t0, t2 - t1, t3 - t2, t4 - t3, t5 - t4, globalTouching.size(), orderedTasks.size());
        return new TaskGraph(finalById, edgesAsSet);
    }

    /**
     * True if buildFast can be used safely for this task collection. Requires
     * that no task writes specific block positions (writtenBlocks must be
     * empty for all tasks, since otherwise self-only tasks could conflict
     * with positional block writers).
     */
    public static boolean isFastPathSafe(Collection<TaskNode> tasks) {
        for (TaskNode task : tasks) {
            RWSet rw = task.declaredRWSet();
            if (!rw.writtenBlocks().isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
