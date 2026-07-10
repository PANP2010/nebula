package org.nebula.entity;

import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskAction;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EventType;
import org.nebula.core.state.WorldPos;

import java.util.Objects;

/**
 * Creates {@link TaskNode}s for fluid tick tasks (arch doc §8.2).
 *
 * <h3>RW-set templates</h3>
 * <ul>
 *   <li><b>WATER_FLOW:</b> reads self + 5 neighbours (down, N/S/E/W), writes possible
 *       flow-to positions, fires BLOCK_UPDATE on depth change</li>
 *   <li><b>LAVA_FLOW:</b> same structure, different depth limit</li>
 *   <li><b>FLUID_REMOVE:</b> reads self, writes self (clears fluid), fires BLOCK_UPDATE</li>
 * </ul>
 *
 * <p>Fluid-redstone interaction (§8.3): fluid tasks declare write-sets including
 * their flow path, so dependency analysis naturally serializes fluid before
 * any redstone component at those positions.
 */
public final class FluidTaskFactory {

    private FluidTaskFactory() {}

    public static TaskNode flow(FluidSnapshot snapshot, TaskAction action) {
        Objects.requireNonNull(snapshot);
        return new TaskNode(
            snapshot.taskId(),
            snapshot.type().taskType(),
            flowRw(snapshot),
            action
        );
    }

    public static TaskNode flowInert(FluidSnapshot snapshot) {
        return flow(snapshot, () -> {});
    }

    public static TaskNode remove(FluidSnapshot snapshot, TaskAction action) {
        Objects.requireNonNull(snapshot);
        String taskId = FluidTaskType.FLUID_REMOVE.taskType() + "@"
            + snapshot.pos().dimensionId() + ":" + snapshot.pos().x()
            + "," + snapshot.pos().y() + "," + snapshot.pos().z();
        return new TaskNode(
            taskId,
            FluidTaskType.FLUID_REMOVE.taskType(),
            removeRw(snapshot),
            action
        );
    }

    public static TaskNode removeInert(FluidSnapshot snapshot) {
        return remove(snapshot, () -> {});
    }

    /**
     * Fluid flow RW-set (arch doc §8.2):
     * reads self + down + 4 horizontal neighbours,
     * writes all positions fluid may flow to.
     */
    private static RWSet flowRw(FluidSnapshot s) {
        WorldPos self = s.pos();
        RWSet.Builder b = RWSet.builder()
            .readBlock(self)
            .readBlock(s.down())
            .readBlock(s.north())
            .readBlock(s.south())
            .readBlock(s.east())
            .readBlock(s.west());

        // Fluid may flow to any of 5 adjacent positions
        b.writeBlock(s.down())
            .writeBlock(s.north())
            .writeBlock(s.south())
            .writeBlock(s.east())
            .writeBlock(s.west());

        b.writeEvent(EventType.BLOCK_UPDATE);
        return b.build();
    }

    /**
     * Fluid removal: reads self, writes self to clear, fires BLOCK_UPDATE.
     */
    private static RWSet removeRw(FluidSnapshot s) {
        return RWSet.builder()
            .readBlock(s.pos())
            .writeBlock(s.pos())
            .writeEvent(EventType.BLOCK_UPDATE)
            .build();
    }
}
