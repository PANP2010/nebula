package org.nebula.core.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Routes tasks to subsystem runners by task type, so multiple subsystems
 * (redstone, entity physics, …) execute in one combined DAG tick.
 *
 * <p>This is the integration point for the cross-subsystem claim at the heart of
 * the architecture: causally-independent tasks — whether redstone or entity —
 * share one dependency graph, and any two that touch disjoint state run in the
 * same layer regardless of which subsystem they belong to. The combined DAG is
 * built once over the union of both subsystems' tasks; this runner just
 * dispatches each task to the {@link LayerCommitting} runner that owns it.
 *
 * <p>Routing rules are tried in registration order; the first matching
 * predicate wins. A task matching no rule is a hard error — silently dropping a
 * task would corrupt the tick — surfaced as {@link IllegalStateException}.
 * {@link #commitLayer()} and {@link #resetLayer()} fan out to every registered
 * sub-runner so a combined layer commits atomically across subsystems.
 */
public final class CompositeTaskRunner implements LayerCommitting {

    private record Route(Predicate<TaskNode> matches, LayerCommitting runner) {}

    private final List<Route> routes = new ArrayList<>();

    /**
     * Registers a sub-runner for tasks matching {@code predicate}. Rules are
     * evaluated in registration order. Returns {@code this} for chaining.
     */
    public CompositeTaskRunner route(Predicate<TaskNode> predicate, LayerCommitting runner) {
        routes.add(new Route(predicate, runner));
        return this;
    }

    /** Convenience: route tasks whose type starts with {@code prefix}. */
    public CompositeTaskRunner routeByTypePrefix(String prefix, LayerCommitting runner) {
        return route(task -> task.taskType().startsWith(prefix), runner);
    }

    @Override
    public void run(TaskNode task) throws Exception {
        // Compound tasks are dispatched to whichever sub-runner owns their
        // members; the member task type is encoded in the compound ID, but we
        // route on the compound's merged identity by inspecting a member. Since
        // SCC contraction only merges mutually-conflicting tasks (which in
        // practice belong to one subsystem), route by the first member's type.
        LayerCommitting target = resolve(task);
        if (target == null) {
            throw new IllegalStateException(
                "No sub-runner routes task type '" + task.taskType() + "' (id=" + task.taskId() + ")");
        }
        target.run(task);
    }

    private LayerCommitting resolve(TaskNode task) {
        String routingType = routingType(task);
        for (Route r : routes) {
            // Match against a synthetic node carrying the routing type so
            // prefix predicates work for compound tasks too.
            if (r.matches.test(task) || (routingType != null && prefixMatch(r, routingType))) {
                return r.runner;
            }
        }
        return null;
    }

    /**
     * For a compound (SCC) task, the routing type is the type of its first
     * member (members of one SCC are mutually conflicting and thus same-subsystem);
     * otherwise the task's own type.
     */
    private static String routingType(TaskNode task) {
        if (!CompoundTask.isCompound(task)) {
            return task.taskType();
        }
        return CompoundTask.memberIds(task).stream()
            .min(DeterministicOrdering::compareTaskIds)
            .map(id -> {
                int at = id.indexOf('@');
                return at < 0 ? id : id.substring(0, at);
            })
            .orElse(null);
    }

    private static boolean prefixMatch(Route r, String routingType) {
        // Only meaningful for prefix routes; we re-test the predicate against a
        // lightweight stand-in node carrying the routing type.
        return r.matches.test(TaskNode.inert(routingType + "@probe", routingType,
            org.nebula.core.rw.RWSet.empty()));
    }

    @Override
    public List<String> commitLayer() {
        List<String> failed = new ArrayList<>();
        for (Route r : routes) {
            failed.addAll(r.runner.commitLayer());
        }
        return failed;
    }

    @Override
    public void resetLayer() {
        for (Route r : routes) {
            r.runner.resetLayer();
        }
    }
}
