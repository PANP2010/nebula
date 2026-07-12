package org.nebula.entity;

import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskAction;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EventType;
import org.nebula.core.state.WorldPos;

/**
 * Creates {@link TaskNode}s for light propagation (arch doc §10, P2.1).
 *
 * <h3>Light task types</h3>
 * <ul>
 *   <li><b>BLOCK_LIGHT:</b> reads block light emission, writes neighbour block light levels</li>
 *   <li><b>SKY_LIGHT:</b> reads sky light (above column), writes neighbour sky light levels</li>
 *   <li><b>LIGHT_PROPAGATE:</b> generic BFS propagation task</li>
 * </ul>
 *
 * <p>Light propagation is implemented as a DAG where each propagation step
 * (from one cell to a neighbour) is a microstep node, allowing the DAG
 * to model the full light spread per tick while respecting dependency
 * ordering between propagation levels.
 */
public final class LightTaskFactory {

    private LightTaskFactory() {}

    /**
     * Block light propagation: reads the emission at a source, computes
     * new light levels at neighbour cells.
     */
    public static TaskNode blockLight(LightSnapshot snapshot, TaskAction action) {
        String taskId = "BLOCK_LIGHT@" + snapshot.pos().dimensionId()
            + ":" + snapshot.pos().x() + "," + snapshot.pos().y() + "," + snapshot.pos().z();
        return new TaskNode(taskId, LightTaskType.BLOCK_LIGHT.taskType(),
            blockLightRw(snapshot), action);
    }

    /**
     * Sky light propagation: reads sky exposure, writes neighbour sky light.
     */
    public static TaskNode skyLight(LightSnapshot snapshot, TaskAction action) {
        String taskId = "SKY_LIGHT@" + snapshot.pos().dimensionId()
            + ":" + snapshot.pos().x() + "," + snapshot.pos().y() + "," + snapshot.pos().z();
        return new TaskNode(taskId, LightTaskType.SKY_LIGHT.taskType(),
            skyLightRw(snapshot), action);
    }

    /**
     * Combined light propagate: reads light at self, writes light at neighbours.
     */
    public static TaskNode propagate(LightSnapshot snapshot, TaskAction action) {
        String taskId = "LIGHT_PROP@" + snapshot.pos().dimensionId()
            + ":" + snapshot.pos().x() + "," + snapshot.pos().y() + "," + snapshot.pos().z();
        return new TaskNode(taskId, LightTaskType.LIGHT_PROPAGATE.taskType(),
            propagateRw(snapshot), action);
    }

    // ── RW-sets ─────────────────────────────────────────────────────────────

    private static RWSet blockLightRw(LightSnapshot s) {
        WorldPos self = s.pos();
        RWSet.Builder b = RWSet.builder()
            .readBlock(self)
            .readBlock(s.down())
            .readBlock(s.north())
            .readBlock(s.south())
            .readBlock(s.east())
            .readBlock(s.west())
            .readBlock(s.up());

        // Write updated light at neighbours
        b.writeBlock(s.down())
            .writeBlock(s.north())
            .writeBlock(s.south())
            .writeBlock(s.east())
            .writeBlock(s.west())
            .writeBlock(s.up())
            .writeBlock(self);

        b.writeEvent(EventType.BLOCK_UPDATE);
        return b.build();
    }

    private static RWSet skyLightRw(LightSnapshot s) {
        WorldPos self = s.pos();
        RWSet.Builder b = RWSet.builder()
            .readBlock(self)
            .readBlock(s.up())
            .readBlock(s.north())
            .readBlock(s.south())
            .readBlock(s.east())
            .readBlock(s.west());

        b.writeBlock(s.north())
            .writeBlock(s.south())
            .writeBlock(s.east())
            .writeBlock(s.west())
            .writeBlock(self);

        b.writeEvent(EventType.BLOCK_UPDATE);
        return b.build();
    }

    private static RWSet propagateRw(LightSnapshot s) {
        WorldPos self = s.pos();
        return RWSet.builder()
            .readBlock(self)
            .readBlock(s.down())
            .readBlock(s.north())
            .readBlock(s.south())
            .readBlock(s.east())
            .readBlock(s.west())
            .readBlock(s.up())
            .writeBlock(s.down())
            .writeBlock(s.north())
            .writeBlock(s.south())
            .writeBlock(s.east())
            .writeBlock(s.west())
            .writeBlock(s.up())
            .writeBlock(self)
            .writeEvent(EventType.BLOCK_UPDATE)
            .build();
    }
}
