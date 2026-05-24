package org.nebula.core.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Dispatches to multiple {@link TaskGenerator}s and merges their outputs.
 *
 * <p>Used by the global tick pipeline to combine redstone, entity, and block entity
 * task generators into a single composite that the {@link MicroStepExtender} can invoke.
 * Each sub-generator is responsible for its own task-type filtering — generators that
 * don't recognize a completed task simply return an empty list.
 */
public final class CompositeTaskGenerator implements TaskGenerator {

    private final List<TaskGenerator> delegates;

    public CompositeTaskGenerator(List<TaskGenerator> delegates) {
        this.delegates = List.copyOf(Objects.requireNonNull(delegates));
    }

    public CompositeTaskGenerator(TaskGenerator... delegates) {
        this(List.of(delegates));
    }

    @Override
    public List<TaskNode> generateFrom(TaskNode completedTask) {
        List<TaskNode> result = null;
        for (TaskGenerator delegate : delegates) {
            List<TaskNode> generated = delegate.generateFrom(completedTask);
            if (!generated.isEmpty()) {
                if (result == null) {
                    result = new ArrayList<>(generated);
                } else {
                    result.addAll(generated);
                }
            }
        }
        return result == null ? List.of() : List.copyOf(result);
    }
}
