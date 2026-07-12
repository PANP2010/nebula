package org.nebula.folia;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.nebula.annotations.MicroStepBehavior;
import org.nebula.annotations.NebulaRW;
import org.nebula.annotations.SccBehavior;
import org.nebula.core.math.Vec3;
import org.nebula.core.state.EntityField;
import org.nebula.entity.EntityPhysicsState;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Bridge between Nebula's AI subsystem (arch doc §7) and the live Bukkit
 * entity surface. Phase 0's {@code AITaskFactory} operates on
 * {@code ai_state.*} fields, with the four-stage pipeline
 * SENSE → GOAL_SELECT → PATHFIND → ACT flowing through the entity CAS store.
 * This bridge is the runtime-classpath landing pad for that subsystem's
 * {@code @NebulaRW} declarations and gives the coverage dashboard
 * (P1.5.3b) a real inventory to count against.
 *
 * <p>The annotated RW-set covers the AI pipeline's standard footprint
 * ({@code AITaskFactory.aiStepRw(...)}):
 * <ul>
 *   <li>{@code position_snapshot}, {@code position} — physical location
 *       (read by SENSE/PATHFIND, written by ACT);</li>
 *   <li>{@code health} — the entity's current health (read by SENSE and
 *       GOAL_SELECT, the fudge factor for goal selection);</li>
 *   <li>{@code ai_state.sensed_entities}, {@code ai_state.sensed_pois} —
 *       the SENSE stage's outputs (read by GOAL_SELECT);</li>
 *   <li>{@code ai_state.current_goal} — GOAL_SELECT's output (read by
 *       PATHFIND and ACT);</li>
 *   <li>{@code ai_state.current_path} — PATHFIND's output (read by ACT);</li>
 *   <li>{@code ai_state.action_result} — ACT's output (read by callers). </li>
 * </ul>
 *
 * <p>The bridge mirrors the conservative-coverage design of
 * {@link NmsBlockEntityStateBridge}: every field the AI pipeline ever
 * touches is declared, so the runtime guard sees the AI bridge's RW-set as
 * a superset of the action's.
 *
 * <p>Like the other bridges, this is the runtime classpath target for
 * {@code @NebulaRW}; NMS itself is not on the runtime classpath, so the
 * annotations here serve the
 * {@link org.nebula.maintenance.BridgeAnnotationScanner} inventory
 * (DG3 Component C, P1.5.3b).
 */
public final class NmsAiStateBridge {

    private static final Logger LOG = Logger.getLogger(NmsAiStateBridge.class.getName());

    private final EntityPhysicsState casStore;

    public NmsAiStateBridge(EntityPhysicsState casStore) {
        this.casStore = Objects.requireNonNull(casStore, "casStore");
    }

    /**
     * Reads the live Bukkit entity's position/health into the AI pipeline's
     * canonical CAS fields. This is the SENSE stage's read counterpart: the
     * position/health values the SENSE/GOAL_SELECT actions then read.
     *
     * <p>Conservative {@code @NebulaRW} coverage: declares the full AI
     * pipeline's read set so the runtime guard treats the bridge's sync as
     * feeding any subsequent AI task on the same entity.
     *
     * <p>Must be called on the region thread that owns the entity.
     */
    @NebulaRW(
        readEntities       = {"{entityId}.position_snapshot", "{entityId}.position",
                              "{entityId}.health",
                              "{entityId}.ai_state.sensed_entities",
                              "{entityId}.ai_state.sensed_pois"},
        writeEntities      = {"{entityId}.position_snapshot", "{entityId}.position",
                              "{entityId}.ai_state.sensed_entities",
                              "{entityId}.ai_state.sensed_pois"},
        triggeredEvents    = {"AI_SENSE_TICK"},
        microStep          = MicroStepBehavior.NONE,
        scc                = SccBehavior.SERIALIZED,
        maxRandomCalls     = 0,
        randomInstance     = "NONE",
        mayLoadChunks      = false,
        mayTriggerBlockUpdates = false,
        maySpawnEntities   = false,
        verifiedAt         = "1.21.4",
        verifiedBy         = {"AITaskFactoryTest", "AiPipelineActionsTest"}
    )
    public void syncFromNms(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (entity.isDead()) return;

        int id = entity.getEntityId();
        Location loc = entity.getLocation();
        casStore.casCommit(new EntityField(id, "position_snapshot"),
            casStore.getVersion(new EntityField(id, "position_snapshot")),
            new Vec3(loc.getX(), loc.getY(), loc.getZ()));
        casStore.casCommit(new EntityField(id, "position"),
            casStore.getVersion(new EntityField(id, "position")),
            new Vec3(loc.getX(), loc.getY(), loc.getZ()));
        if (entity instanceof LivingEntity le) {
            casStore.casCommit(new EntityField(id, "health"),
                casStore.getVersion(new EntityField(id, "health")),
                (double) le.getHealth());
        }
        LOG.fine(() -> "AI bridge: synced SENSE inputs for entity " + id);
    }

    /**
     * Reads the AI pipeline's outputs (position, action_result) and pushes
     * them onto the live entity. The GOAL_SELECT/PATHFIND/ACT stages
     * produce these as their final write set; this method is the bridge
     * that flows the DAG's value back to NMS.
     *
     * <p>Conservative {@code @NebulaRW} coverage: declares the full AI
     * pipeline's write set so the runtime guard treats the bridge's write
     * as authoritative for any subsequent AI task on the same entity.
     *
     * <p>Must be called on the region thread that owns the entity.
     */
    @NebulaRW(
        readEntities       = {"{entityId}.ai_state.current_goal",
                              "{entityId}.ai_state.current_path",
                              "{entityId}.ai_state.action_result",
                              "{entityId}.position"},
        writeEntities      = {"{entityId}.ai_state.current_goal",
                              "{entityId}.ai_state.current_path",
                              "{entityId}.ai_state.action_result",
                              "{entityId}.position"},
        triggeredEvents    = {"AI_ACT_TICK", "ENTITY_MOVED"},
        microStep          = MicroStepBehavior.NONE,
        scc                = SccBehavior.SERIALIZED,
        maxRandomCalls     = 0,
        randomInstance     = "NONE",
        mayLoadChunks      = false,
        mayTriggerBlockUpdates = false,
        maySpawnEntities   = false,
        verifiedAt         = "1.21.4",
        verifiedBy         = {"AiPipelineActionsTest"}
    )
    public void syncToNms(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (entity.isDead()) return;

        int id = entity.getEntityId();
        Vec3 pos = casStore.getVec(new EntityField(id, "position"));
        if (pos == null || pos.equals(Vec3.ZERO)) {
            LOG.fine(() -> "AI bridge: no position in CAS for entity " + id + " — skipping teleport");
            return;
        }
        org.bukkit.Location target = entity.getLocation();
        target.setX(pos.x());
        target.setY(pos.y());
        target.setZ(pos.z());
        entity.teleport(target);

        Vec3 result = casStore.getVec(new EntityField(id, "ai_state.action_result"));
        if (result != null) {
            LOG.fine(() -> "AI bridge: action_result for entity " + id + " = " + result);
        }
    }

    /**
     * Reads the live Bukkit entity's position/health and writes the
     * SENSE/GOAL_SELECT outputs in a single round-trip. This is the
     * pipeline-friendly entry point for callers that want a single sync
     * covering both ends of the AI tick.
     *
     * <p>Conservative {@code @NebulaRW} coverage: declares the union of
     * {@link #syncFromNms} and {@link #syncToNms} so the runtime guard
     * treats this method as depending on (and feeding) every AI task on
     * the entity.
     *
     * <p>Must be called on the region thread that owns the entity.
     */
    @NebulaRW(
        readEntities       = {"{entityId}.position_snapshot", "{entityId}.position",
                              "{entityId}.health",
                              "{entityId}.ai_state.sensed_entities",
                              "{entityId}.ai_state.sensed_pois",
                              "{entityId}.ai_state.current_goal",
                              "{entityId}.ai_state.current_path",
                              "{entityId}.ai_state.action_result"},
        writeEntities      = {"{entityId}.position_snapshot", "{entityId}.position",
                              "{entityId}.ai_state.sensed_entities",
                              "{entityId}.ai_state.sensed_pois",
                              "{entityId}.ai_state.current_goal",
                              "{entityId}.ai_state.current_path",
                              "{entityId}.ai_state.action_result"},
        triggeredEvents    = {"AI_SENSE_TICK", "AI_ACT_TICK", "ENTITY_MOVED"},
        microStep          = MicroStepBehavior.NONE,
        scc                = SccBehavior.SERIALIZED,
        maxRandomCalls     = 3,
        randomInstance     = "ENTITY_RANDOM",
        mayLoadChunks      = false,
        mayTriggerBlockUpdates = false,
        maySpawnEntities   = false,
        verifiedAt         = "1.21.4",
        verifiedBy         = {"AiPipelineActionsTest"}
    )
    public void syncPipeline(Entity entity) {
        syncFromNms(entity);
        syncToNms(entity);
    }

    /**
     * Bulk variant: syncs the SENSE inputs for every entity in a collection.
     * Used by the per-tick AI pipeline to push a whole region's worth of
     * entities through the bridge in one call.
     *
     * <p>Conservative {@code @NebulaRW} coverage: same per-entity union as
     * {@link #syncFromNms}.
     *
     * <p>Must be called on the region thread that owns the entities.
     */
    @NebulaRW(
        readEntities       = {"{entityId}.position_snapshot", "{entityId}.position",
                              "{entityId}.health",
                              "{entityId}.ai_state.sensed_entities",
                              "{entityId}.ai_state.sensed_pois"},
        writeEntities      = {"{entityId}.position_snapshot", "{entityId}.position",
                              "{entityId}.ai_state.sensed_entities",
                              "{entityId}.ai_state.sensed_pois"},
        triggeredEvents    = {"AI_SENSE_TICK"},
        microStep          = MicroStepBehavior.NONE,
        scc                = SccBehavior.SERIALIZED,
        maxRandomCalls     = 0,
        randomInstance     = "NONE",
        mayLoadChunks      = false,
        mayTriggerBlockUpdates = false,
        maySpawnEntities   = false,
        verifiedAt         = "1.21.4",
        verifiedBy         = {"NmsAiStateBridgeTest"}
    )
    public int bulkSyncFromNms(Collection<? extends Entity> entities) {
        Objects.requireNonNull(entities, "entities");
        int n = 0;
        for (Entity e : entities) {
            if (e == null || e.isDead()) continue;
            syncFromNms(e);
            n++;
        }
        return n;
    }

    /**
     * Returns the CAS store this bridge is bound to.
     */
    @NebulaRW(
        microStep          = MicroStepBehavior.NONE,
        scc                = SccBehavior.AUTO,
        maxRandomCalls     = 0,
        randomInstance     = "NONE",
        mayLoadChunks      = false,
        mayTriggerBlockUpdates = false,
        maySpawnEntities   = false,
        verifiedAt         = "1.21.4",
        verifiedBy         = {"NmsAiStateBridgeTest"}
    )
    public EntityPhysicsState casStore() {
        return casStore;
    }

    /**
     * Locates a live entity in {@code world} by id. Convenience for callers
     * that already have the entity id but not the live reference.
     */
    public static Optional<Entity> findEntityById(org.bukkit.World world, int entityId) {
        for (Entity e : world.getEntities()) {
            if (e.getEntityId() == entityId) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }
}
