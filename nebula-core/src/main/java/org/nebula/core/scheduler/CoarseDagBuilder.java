package org.nebula.core.scheduler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a legal-but-suboptimal coarse-grained DAG (arch doc §4.3.1;
 * NEBULA-PATCH-2026-001 §变更二).
 *
 * <p>This is the fallback the DAG-build degrade path falls back <em>to</em> when
 * the build-time budget is exhausted: rather than risk an avalanche of
 * per-tick timeouts trying to compute the optimal parallel DAG, the builder
 * emits a coarse serial ordering that is guaranteed legal — a topological sort
 * always exists because a chain has no cycles.
 *
 * <p>Tasks are ordered by deterministic task-ID comparison and chained
 * {@code a → b → c → …}. Correctness is preserved (every conflicting pair is
 * ordered, because <em>all</em> pairs are ordered); only parallelism is lost.
 * This matches the patch's "粗粒度串行块" — coarse serial block executed in
 * task-ID order.
 */
public final class CoarseDagBuilder {

    private CoarseDagBuilder() {}

    /**
     * Produces a fully-serial {@link TaskGraph} over {@code tasks}: a single
     * chain in deterministic task-ID order. Always a legal DAG.
     */
    public static TaskGraph serialChain(Collection<TaskNode> tasks) {
        Map<String, TaskNode> byId = new LinkedHashMap<>();
        for (TaskNode t : tasks) {
            if (byId.put(t.taskId(), t) != null) {
                throw new IllegalArgumentException("duplicate task id: " + t.taskId());
            }
        }
        if (byId.size() <= 1) {
            return new TaskGraph(byId, Set.of());
        }

        List<String> ordered = new ArrayList<>(byId.keySet());
        ordered.sort(DeterministicOrdering::compareTaskIds);

        Set<DependencyEdge> edges = new LinkedHashSet<>();
        for (int i = 0; i + 1 < ordered.size(); i++) {
            // WAW is the order-only dependency type; a chain edge just pins order.
            edges.add(new DependencyEdge(ordered.get(i), ordered.get(i + 1), DependencyType.WAW));
        }
        return new TaskGraph(byId, edges);
    }
}
