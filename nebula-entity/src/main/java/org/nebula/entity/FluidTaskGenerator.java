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
 * Micro-step task generator for fluid propagation (arch doc §8.1-8.3).
 *
 * <p>When a fluid block updates and its depth changes, it propagates to
 * adjacent positions that contain fluid or empty space. Propagation priority:
 * down first, then horizontal (N/S/E/W).
 *
 * <p>Fluid tasks that do not change depth (fixed point) do not propagate.
 */
public final class FluidTaskGenerator implements TaskGenerator {

    private final Map<WorldPos, FluidSnapshot> fluidMap;

    public FluidTaskGenerator(Map<WorldPos, FluidSnapshot> fluidMap) {
        this.fluidMap = Objects.requireNonNull(fluidMap);
    }

    @Override
    public List<TaskNode> generateFrom(TaskNode completedTask) {
        FluidTaskType type = FluidTaskType.fromTaskType(completedTask.taskType());
        if (type == null) return List.of();

        if (type == FluidTaskType.FLUID_REMOVE) return List.of();

        if (!completedTask.declaredRWSet().writtenEvents().contains(EventType.BLOCK_UPDATE)) {
            return List.of();
        }

        WorldPos selfPos = parsePos(completedTask.taskId());
        if (selfPos == null) return List.of();

        FluidSnapshot self = fluidMap.get(selfPos);
        if (self == null) return List.of();

        // Propagate to adjacent fluid positions
        List<TaskNode> downstream = new ArrayList<>();
        WorldPos[] neighbours = {
            self.down(), self.north(), self.south(), self.east(), self.west()
        };

        for (WorldPos neighbour : neighbours) {
            FluidSnapshot neighbourFluid = fluidMap.get(neighbour);
            if (neighbourFluid != null) {
                downstream.add(FluidTaskFactory.flowInert(neighbourFluid));
            }
        }

        return List.copyOf(downstream);
    }

    static WorldPos parsePos(String taskId) {
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
            return new WorldPos(dim,
                Integer.parseInt(coords[0]),
                Integer.parseInt(coords[1]),
                Integer.parseInt(coords[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
