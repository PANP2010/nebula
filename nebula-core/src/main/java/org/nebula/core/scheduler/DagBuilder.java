package org.nebula.core.scheduler;

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
}
