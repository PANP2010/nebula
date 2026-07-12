package org.nebula.core.scheduler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Contracts SCCs in a task graph so the result is a DAG suitable for
 * topological layering.
 *
 * <p>Strategy (§2.2 of the architecture doc):
 * <ul>
 *   <li>SCCs with {@code size ≤ threshold} are contracted into a single
 *       {@link CompoundTask} whose action runs members in deterministic order.</li>
 *   <li>SCCs exceeding the threshold are serialised (sorted by task ID) and
 *       reported as warnings — this is a safety escape hatch, not a normal path.
 *       Under {@link org.nebula.core.random.FidelityTier#allowsLargeScc() relaxed}
 *       fidelity tiers (T2/T3), the threshold is bumped to
 *       {@link #LARGE_THRESHOLD} so collision-response SCCs can still be
 *       contracted in one batch instead of going through the safety hatch.</li>
 * </ul>
 */
public final class SccContractor {

    private static final Logger LOG = Logger.getLogger(SccContractor.class.getName());

    /** Default node-count threshold below which an SCC is contracted (arch doc: 128). */
    public static final int DEFAULT_THRESHOLD = 128;

    /**
     * Threshold used under T2+ (large-SCC mode). Picked at 1024 to absorb
     * pathological entity collision SCCs (one per chunk-pair in worst case)
     * without serialising. Falls back to {@link Long#MAX_VALUE}-equivalent
     * under {@link #UNLIMITED_THRESHOLD}.
     */
    public static final int LARGE_THRESHOLD = 1024;

    /** Sentinel for "never serialise, always contract" mode. */
    public static final int UNLIMITED_THRESHOLD = Integer.MAX_VALUE;

    /**
     * Threshold used by the no-arg constructor. Set via
     * {@link #setDefaultThreshold(int)} from the fidelity hook — the
     * per-call {@link SccContractor(int)} constructor still wins for explicit
     * callers, so unit tests are unaffected.
     */
    private static volatile int defaultThreshold = DEFAULT_THRESHOLD;

    /** Overrides the threshold applied by the no-arg constructor. */
    public static void setDefaultThreshold(int threshold) {
        if (threshold < 1) {
            throw new IllegalArgumentException("threshold must be >= 1");
        }
        defaultThreshold = threshold;
    }

    /** Current no-arg-constructor threshold. */
    public static int defaultThreshold() {
        return defaultThreshold;
    }

    private int threshold;

    public SccContractor() {
        this(defaultThreshold);
    }

    public SccContractor(int threshold) {
        if (threshold < 1) {
            throw new IllegalArgumentException("threshold must be >= 1");
        }
        this.threshold = threshold;
    }

    /**
     * Replaces the contraction threshold. Used by T2+ relaxed modes — see
     * {@link org.nebula.core.scheduler.FidelityDowngradeController}. Caller is
     * responsible for re-running DAG builds; existing contracted graphs are
     * unaffected.
     */
    public void setThreshold(int threshold) {
        if (threshold < 1) {
            throw new IllegalArgumentException("threshold must be >= 1");
        }
        this.threshold = threshold;
    }

    /** Current threshold — exposed for telemetry / /nebula dag-stats. */
    public int threshold() {
        return threshold;
    }

    /**
     * Returns a new (tasks, edges) pair with all SCCs contracted or serialised.
     * If the graph is already acyclic the inputs are returned unchanged (copy-of).
     */
    public ContractionResult contract(Collection<TaskNode> tasks, Collection<DependencyEdge> edges) {
        // Only nodes that appear as an endpoint of some edge can possibly be in
        // a non-trivial SCC (a cycle requires at least one in- and out-edge).
        // Isolated nodes are always singleton SCCs, which Tarjan would discard
        // anyway. So run Tarjan over just the edge-induced node set instead of
        // all N tasks — with ~8000 entity/BE tasks but only ~29 edge-touching
        // ones, this drops the SCC phase from O(N) map/list churn to O(E).
        Set<String> edgeNodes = new LinkedHashSet<>();
        for (DependencyEdge e : edges) {
            edgeNodes.add(e.sourceTaskId());
            edgeNodes.add(e.targetTaskId());
        }
        List<Set<String>> sccs = TarjanScc.compute(edgeNodes, edges);

        if (sccs.isEmpty()) {
            SccStats.record(0, 0, 0, 0);
            return new ContractionResult(List.copyOf(tasks), List.copyOf(edges), List.of());
        }

        // Build lookup: original task ID → TaskNode
        Map<String, TaskNode> byId = new LinkedHashMap<>();
        for (TaskNode t : tasks) {
            byId.put(t.taskId(), t);
        }

        // Build lookup: original task ID → replacement task ID (compound or first-serial)
        Map<String, String> replacementId = new HashMap<>();

        List<TaskNode> newTasks = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // SCC telemetry (surfaced via /nebula scc).
        int contractedCount = 0;
        int serialisedCount = 0;
        int maxSccSize = 0;

        for (Set<String> scc : sccs) {
            if (scc.size() > maxSccSize) {
                maxSccSize = scc.size();
            }
            List<TaskNode> members = scc.stream()
                .filter(byId::containsKey)
                .map(byId::get)
                .toList();

            if (members.size() <= threshold) {
                contractedCount++;
                TaskNode compound = CompoundTask.of(members);
                newTasks.add(compound);
                for (String memberId : scc) {
                    replacementId.put(memberId, compound.taskId());
                }
                LOG.fine(() -> "Contracted SCC (" + scc.size() + " nodes) into " + compound.taskId());
            } else {
                serialisedCount++;
                // Oversized: serialise by keeping all members, adding serial edges
                warnings.add("Oversized SCC (" + scc.size() + " nodes) serialised: " + scc);
                LOG.warning("Oversized SCC (" + scc.size() + " nodes) — serialising. "
                    + "Check for abnormal game logic or potential bug.");
                // All members keep their own IDs; no replacement needed
                for (TaskNode m : members) {
                    newTasks.add(m);
                }
            }
        }

        // Add tasks that were not part of any SCC
        Set<String> sccMembers = new LinkedHashSet<>();
        for (Set<String> scc : sccs) {
            sccMembers.addAll(scc);
        }
        for (TaskNode t : tasks) {
            if (!sccMembers.contains(t.taskId())) {
                newTasks.add(t);
            }
        }

        // Re-map edges: replace member IDs with compound IDs, drop intra-compound edges
        // and strip all original intra-SCC edges for oversized SCCs
        Set<DependencyEdge> newEdges = new LinkedHashSet<>();

        // For oversized SCCs: add deterministic serial edges between members
        // and track their member IDs so we can strip the original cycle edges
        Set<String> oversizedMembers = new LinkedHashSet<>();
        for (Set<String> scc : sccs) {
            if (scc.size() > threshold) {
                oversizedMembers.addAll(scc);
                List<String> sorted = scc.stream().sorted().toList();
                for (int i = 0; i < sorted.size() - 1; i++) {
                    newEdges.add(new DependencyEdge(sorted.get(i), sorted.get(i + 1), DependencyType.WAW));
                }
            }
        }

        for (DependencyEdge edge : edges) {
            String src = replacementId.getOrDefault(edge.sourceTaskId(), edge.sourceTaskId());
            String tgt = replacementId.getOrDefault(edge.targetTaskId(), edge.targetTaskId());
            if (src.equals(tgt)) {
                continue; // intra-compound edge, drop it
            }
            // Drop original edges within an oversized SCC — the serial chain replaces them
            if (oversizedMembers.contains(edge.sourceTaskId()) && oversizedMembers.contains(edge.targetTaskId())) {
                continue;
            }
            newEdges.add(new DependencyEdge(src, tgt, edge.type()));
        }

        SccStats.record(sccs.size(), contractedCount, serialisedCount, maxSccSize);
        return new ContractionResult(List.copyOf(newTasks), List.copyOf(newEdges), List.copyOf(warnings));
    }

    public record ContractionResult(
        List<TaskNode> tasks,
        List<DependencyEdge> edges,
        List<String> oversizedSccWarnings
    ) {}
}
