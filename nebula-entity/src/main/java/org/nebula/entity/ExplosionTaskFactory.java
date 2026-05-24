package org.nebula.entity;

import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskAction;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.EventType;
import org.nebula.core.state.RandomInstance;
import org.nebula.core.state.RandomUsage;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Creates task nodes for explosion sub-DAG layers (arch doc §9.1).
 *
 * <p>An explosion is modeled as a dynamic sub-DAG:
 * <ul>
 *   <li>Layer 0: parallel ray trace tasks (one per ray group)</li>
 *   <li>Layer 1: single destruction-collect task (aggregates ray results)</li>
 *   <li>Layer 2: parallel block-destroy tasks (grouped by region)</li>
 *   <li>Layer 3: parallel entity-damage tasks (one per affected entity)</li>
 * </ul>
 *
 * <p>The full sub-DAG RW-set covers all affected blocks and entities, enabling
 * the main DAG to schedule explosions correctly relative to other tasks.
 */
public final class ExplosionTaskFactory {

    private ExplosionTaskFactory() {}

    private static final int RAYS_PER_TASK = 16;

    /**
     * Generates the complete sub-DAG task list for one explosion.
     * All tasks are returned — the DagBuilder's conflict detection handles ordering.
     */
    public static List<TaskNode> createSubDag(ExplosionSnapshot explosion) {
        Objects.requireNonNull(explosion);
        List<TaskNode> tasks = new ArrayList<>();

        // Layer 0: Ray trace tasks
        int rayCount = explosion.rayCount();
        int rayTaskCount = Math.max(1, (rayCount + RAYS_PER_TASK - 1) / RAYS_PER_TASK);
        for (int i = 0; i < rayTaskCount; i++) {
            tasks.add(rayTrace(explosion, i));
        }

        // Layer 1: Destruction collect
        tasks.add(destructionCollect(explosion));

        // Layer 2: Block destroy (one task per 64-block group)
        List<List<WorldPos>> blockGroups = partition(
            new ArrayList<>(explosion.affectedBlocks()), 64);
        for (int i = 0; i < blockGroups.size(); i++) {
            tasks.add(blockDestroy(explosion, i, blockGroups.get(i)));
        }

        // Layer 3: Entity damage (one per entity)
        for (long entityId : explosion.affectedEntities()) {
            tasks.add(entityDamage(explosion, entityId));
        }

        return List.copyOf(tasks);
    }

    // ── Individual task factories ─────────────────────────────────────────────

    static TaskNode rayTrace(ExplosionSnapshot explosion, int groupIndex) {
        String taskId = explosion.explosionId() + "/ray-" + groupIndex;
        // Ray trace is pure read (reads blocks along ray paths)
        RWSet rw = RWSet.builder()
            .readBlock(explosion.center())
            .build();
        // In practice, reads all blocks along each ray — simplified to center for template
        return new TaskNode(taskId, ExplosionTaskType.RAY_TRACE.taskType(), rw, () -> {});
    }

    static TaskNode destructionCollect(ExplosionSnapshot explosion) {
        String taskId = explosion.explosionId() + "/collect";
        // Reads all affected blocks to determine final destruction list
        RWSet.Builder b = RWSet.builder();
        for (WorldPos pos : explosion.affectedBlocks()) {
            b.readBlock(pos);
        }
        return new TaskNode(taskId, ExplosionTaskType.DESTRUCTION_COLLECT.taskType(), b.build(), () -> {});
    }

    static TaskNode blockDestroy(ExplosionSnapshot explosion, int groupIndex, List<WorldPos> blocks) {
        String taskId = explosion.explosionId() + "/destroy-" + groupIndex;
        RWSet.Builder b = RWSet.builder();
        for (WorldPos pos : blocks) {
            b.readBlock(pos);
            b.writeBlock(pos);
        }
        b.writeEvent(EventType.BLOCK_UPDATE);
        b.writeEvent(EventType.ENTITY_SPAWNED); // drop items
        b.randomUsage(new RandomUsage(RandomInstance.WORLD_RANDOM, blocks.size()));
        return new TaskNode(taskId, ExplosionTaskType.BLOCK_DESTROY.taskType(), b.build(), () -> {});
    }

    static TaskNode entityDamage(ExplosionSnapshot explosion, long entityId) {
        String taskId = explosion.explosionId() + "/damage-" + entityId;
        RWSet rw = RWSet.builder()
            .readEntity(new EntityField(entityId, "position"))
            .readEntity(new EntityField(entityId, "health"))
            .writeEntity(new EntityField(entityId, "health"))
            .writeEntity(new EntityField(entityId, "velocity"))
            .randomUsage(new RandomUsage(RandomInstance.ENTITY_RANDOM, 2))
            .build();
        return new TaskNode(taskId, ExplosionTaskType.ENTITY_DAMAGE.taskType(), rw, () -> {});
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(List.copyOf(list.subList(i, Math.min(i + size, list.size()))));
        }
        if (partitions.isEmpty()) {
            partitions.add(List.of());
        }
        return partitions;
    }
}
