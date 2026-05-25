package org.nebula.redstone;

import org.nebula.annotations.MicroStepBehavior;
import org.nebula.core.scheduler.TaskGenerator;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EventType;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Micro-step task generator for redstone propagation (arch doc §5.3).
 *
 * <p>When a redstone component's output changes, it may trigger block updates
 * on its neighbours. This generator inspects the completed task's write events
 * and creates downstream redstone tasks for affected components.
 *
 * <p>Two modes of operation:
 * <ul>
 *   <li><b>Change-aware (recommended):</b> use {@link #generateFromChanges(Set)}
 *       after a layer commits, passing only positions whose power actually changed.
 *       This prevents spurious propagation (§5.3 fixed-point condition).</li>
 *   <li><b>Declared-event mode (legacy):</b> use {@link #generateFrom(TaskNode)}
 *       which triggers on any task declaring BLOCK_UPDATE write events.
 *       Less precise but backward-compatible with TaskGenerator interface.</li>
 * </ul>
 *
 * <p>Components whose {@link RedstoneComponentType#microStepBehavior()} is
 * {@code DEFERRED} do not generate new tasks within the same tick.
 */
public final class RedstoneTaskGenerator implements TaskGenerator {

    private static final int[][] NEIGHBOURS = {
        {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}
    };

    private final Map<WorldPos, RedstoneComponentType> componentMap;
    private final Map<String, RedstoneTaskAction> actionRegistry;

    /**
     * @param componentMap  maps every redstone-component position to its type
     * @param actionRegistry maps task type to action; if null, generates inert tasks
     */
    public RedstoneTaskGenerator(Map<WorldPos, RedstoneComponentType> componentMap,
                                  Map<String, RedstoneTaskAction> actionRegistry) {
        this.componentMap = Objects.requireNonNull(componentMap);
        this.actionRegistry = actionRegistry != null ? actionRegistry : Map.of();
    }

    public RedstoneTaskGenerator(Map<WorldPos, RedstoneComponentType> componentMap) {
        this(componentMap, Map.of());
    }

    /**
     * Change-aware generation (arch doc §5.3 preferred path).
     *
     * <p>After a layer commits, call this with the set of positions whose power
     * level actually changed. Generates downstream tasks only for positions
     * adjacent to actual changes, filtering out DEFERRED components.
     *
     * @param changedPositions positions whose power level changed after commit
     * @return new tasks to add to the next microstep layer
     */
    public List<TaskNode> generateFromChanges(Set<WorldPos> changedPositions) {
        List<TaskNode> downstream = new ArrayList<>();

        for (WorldPos changed : changedPositions) {
            // Check if the changed position itself is a DEFERRED component
            RedstoneComponentType changedType = componentMap.get(changed);
            if (changedType != null && changedType.microStepBehavior() == MicroStepBehavior.DEFERRED) {
                continue;
            }

            for (int[] d : NEIGHBOURS) {
                WorldPos neighbour = new WorldPos(
                    changed.dimensionId(),
                    changed.x() + d[0],
                    changed.y() + d[1],
                    changed.z() + d[2]);
                RedstoneComponentType neighbourType = componentMap.get(neighbour);
                if (neighbourType != null
                        && neighbourType.microStepBehavior() != MicroStepBehavior.DEFERRED) {
                    downstream.add(createTask(neighbourType, neighbour));
                }
            }
        }

        return List.copyOf(downstream);
    }

    @Override
    public List<TaskNode> generateFrom(TaskNode completedTask) {
        if (!completedTask.declaredRWSet().writtenEvents().contains(EventType.BLOCK_UPDATE)) {
            return List.of();
        }

        RedstoneComponentType type = resolveType(completedTask);
        if (type == null || type.microStepBehavior() == MicroStepBehavior.DEFERRED) {
            return List.of();
        }

        List<TaskNode> downstream = new ArrayList<>();

        for (WorldPos written : completedTask.declaredRWSet().writtenBlocks()) {
            for (int[] d : NEIGHBOURS) {
                WorldPos neighbour = new WorldPos(
                    written.dimensionId(),
                    written.x() + d[0],
                    written.y() + d[1],
                    written.z() + d[2]);
                RedstoneComponentType neighbourType = componentMap.get(neighbour);
                if (neighbourType != null
                        && neighbourType.microStepBehavior() != MicroStepBehavior.DEFERRED) {
                    downstream.add(createTask(neighbourType, neighbour));
                }
            }
        }

        return List.copyOf(downstream);
    }

    private TaskNode createTask(RedstoneComponentType type, WorldPos pos) {
        RedstoneTaskAction action = actionRegistry.get(type.taskType());
        if (action != null) {
            // Create a task with a placeholder action — the actual execution
            // goes through RedstoneTaskRunner which looks up actions by type
            return RedstoneTaskFactory.inert(type, pos);
        }
        return RedstoneTaskFactory.inert(type, pos);
    }

    static RedstoneComponentType resolveType(TaskNode task) {
        for (RedstoneComponentType t : RedstoneComponentType.values()) {
            if (t.taskType().equals(task.taskType())) {
                return t;
            }
        }
        return null;
    }

    static WorldPos parsePosition(String taskId) {
        int at = taskId.indexOf('@');
        if (at < 0) return null;
        String[] parts = taskId.substring(at + 1).split(":");
        if (parts.length != 2) return null;
        String[] xyz = parts[1].split(",");
        if (xyz.length != 3) return null;
        try {
            return new WorldPos(
                Integer.parseInt(parts[0]),
                Integer.parseInt(xyz[0]),
                Integer.parseInt(xyz[1]),
                Integer.parseInt(xyz[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
