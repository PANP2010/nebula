package org.nebula.entity;

import org.nebula.core.scheduler.TaskGenerator;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EventType;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Micro-step task generator for block entity ticks (arch doc §3.3, §14.3).
 *
 * <p>Hopper chains are the primary propagation path: when a hopper pushes items
 * into an output container that is itself a hopper, that downstream hopper must
 * re-tick within the same game tick to maintain vanilla item-transfer order.
 *
 * <p>Furnaces, brewing stands, droppers, and dispensers do NOT propagate —
 * they are self-contained within a single tick.
 */
public final class BlockEntityTaskGenerator implements TaskGenerator {

    private final Map<WorldPos, BlockEntitySnapshot> blockEntityMap;

    public BlockEntityTaskGenerator(Map<WorldPos, BlockEntitySnapshot> blockEntityMap) {
        this.blockEntityMap = Objects.requireNonNull(blockEntityMap);
    }

    @Override
    public List<TaskNode> generateFrom(TaskNode completedTask) {
        BlockEntityTaskType type = BlockEntityTaskType.fromTaskType(completedTask.taskType());
        if (type == null) return List.of();

        return switch (type) {
            case HOPPER -> generateDownstreamHopper(completedTask);
            default -> List.of();
        };
    }

    private List<TaskNode> generateDownstreamHopper(TaskNode hopperTask) {
        if (!hopperTask.declaredRWSet().writtenEvents().contains(EventType.INVENTORY_CHANGED)) {
            return List.of();
        }

        WorldPos outputPos = extractOutputPos(hopperTask.taskId());
        if (outputPos == null) return List.of();

        BlockEntitySnapshot downstream = blockEntityMap.get(outputPos);
        if (downstream == null || downstream.type() != BlockEntityTaskType.HOPPER) {
            return List.of();
        }

        List<TaskNode> result = new ArrayList<>(1);
        result.add(BlockEntityTaskFactory.hopperInert(downstream));
        return List.copyOf(result);
    }

    /**
     * Extracts the output position from the hopper task's write set.
     * The hopper's output pos is determined by its snapshot's facing direction.
     * We look it up from the blockEntityMap using the task's own position.
     */
    private WorldPos extractOutputPos(String taskId) {
        WorldPos selfPos = parseTaskIdPos(taskId);
        if (selfPos == null) return null;

        BlockEntitySnapshot self = blockEntityMap.get(selfPos);
        if (self == null) return null;

        return self.outputPos();
    }

    /**
     * Parses position from task ID format: {@code BLOCK_ENTITY_HOPPER@dim:x,y,z}
     */
    static WorldPos parseTaskIdPos(String taskId) {
        int atIdx = taskId.indexOf('@');
        if (atIdx < 0) return null;
        String rest = taskId.substring(atIdx + 1);
        int colonIdx = rest.indexOf(':');
        if (colonIdx < 0) return null;
        int dim;
        try {
            dim = Integer.parseInt(rest.substring(0, colonIdx));
        } catch (NumberFormatException e) {
            return null;
        }
        String[] coords = rest.substring(colonIdx + 1).split(",");
        if (coords.length != 3) return null;
        try {
            return new WorldPos(dim, Integer.parseInt(coords[0]), Integer.parseInt(coords[1]), Integer.parseInt(coords[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
