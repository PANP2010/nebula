package org.nebula.folia;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.nebula.annotations.MicroStepBehavior;
import org.nebula.annotations.NebulaRW;
import org.nebula.annotations.SccBehavior;
import org.nebula.core.state.EntityField;
import org.nebula.entity.EntityPhysicsState;
import org.nebula.entity.Vec3;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Bridge between Nebula's collision subsystem (arch doc §6) and the live
 * Bukkit entity surface. Phase 0's {@code EntityCollisionResponseAction}
 * runs the post-detection impulse math; this bridge is the runtime-classpath
 * landing pad for that subsystem's {@code @NebulaRW} declarations and gives
 * the coverage dashboard (P1.5.3b) a real inventory to count against.
 *
 * <p>Collision detection is split into two phases per arch doc §6.2:
 * <ol>
 *   <li><b>Collision detection (pure read)</b> — read both entities'
 *       position fields, output the collision-result list. No state
 *       mutation.</li>
 *   <li><b>Collision response (impulse write)</b> — apply the impulse,
 *       mutating both entities' {@code velocity} and (for {@code MOVE}
 *       actions) {@code position} fields.</li>
 * </ol>
 *
 * <p>The annotated RW-set covers both phases' field union: every entity's
 * {@code position} and {@code velocity}. This is the conservative footprint
 * — the runtime guard treats any collision task touching one of these
 * fields as depending on this bridge's sync.
 *
 * <p>Like the other bridges, this is the runtime classpath target for
 * {@code @NebulaRW}; NMS itself is not on the runtime classpath, so the
 * annotations here serve the
 * {@link org.nebula.maintenance.BridgeAnnotationScanner} inventory
 * (DG3 Component C, P1.5.3b).
 */
public final class NmsCollisionStateBridge {

    private static final Logger LOG = Logger.getLogger(NmsCollisionStateBridge.class.getName());

    private final EntityPhysicsState casStore;

    public NmsCollisionStateBridge(EntityPhysicsState casStore) {
        this.casStore = Objects.requireNonNull(casStore, "casStore");
    }

    /**
     * Reads both entities' position into the CAS store before the
     * collision-detection phase runs. Detection is a pure-read step
     * (arch doc §6.1), so this method only writes the position field; the
     * velocity is left to the response phase.
     *
     * <p>Conservative {@code @NebulaRW} coverage: declares the per-entity
     * {@code position} field as the read/write pair the detection phase
     * needs.
     *
     * <p>Must be called on the region thread that owns both entities.
     */
    @NebulaRW(
        readEntities       = {"{entityId}.position"},
        writeEntities      = {"{entityId}.position"},
        triggeredEvents    = {"COLLISION_DETECTED"},
        microStep          = MicroStepBehavior.NONE,
        scc                = SccBehavior.SERIALIZED,
        maxRandomCalls     = 0,
        randomInstance     = "NONE",
        mayLoadChunks      = false,
        mayTriggerBlockUpdates = false,
        maySpawnEntities   = false,
        verifiedAt         = "1.21.4",
        verifiedBy         = {"EntityCollisionResponseActionTest"}
    )
    public void syncFromNms(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (entity.isDead()) return;

        int id = entity.getEntityId();
        Location loc = entity.getLocation();
        casStore.casCommit(new EntityField(id, "position"),
            casStore.getVersion(new EntityField(id, "position")),
            new Vec3(loc.getX(), loc.getY(), loc.getZ()));
        LOG.fine(() -> "Collision bridge: synced position for entity " + id);
    }

    /**
     * Applies the post-detection response: reads the response-computed
     * velocity, writes it back to NMS. The detection result is the input
     * to {@code EntityCollisionResponseAction}, which derives the impulse
     * and stores the resulting velocity in CAS; this method is the bridge
     * that flows the DAG's value back to NMS.
     *
     * <p>Conservative {@code @NebulaRK} coverage: declares the per-entity
     * {@code velocity} field as the read/write pair the response phase
     * needs.
     *
     * <p>Must be called on the region thread that owns the entity.
     */
    @NebulaRW(
        readEntities       = {"{entityId}.velocity"},
        writeEntities      = {"{entityId}.velocity"},
        triggeredEvents    = {"COLLISION_RESPONSE", "ENTITY_MOVED"},
        microStep          = MicroStepBehavior.NONE,
        scc                = SccBehavior.SERIALIZED,
        maxRandomCalls     = 0,
        randomInstance     = "NONE",
        mayLoadChunks      = false,
        mayTriggerBlockUpdates = false,
        maySpawnEntities   = false,
        verifiedAt         = "1.21.4",
        verifiedBy         = {"EntityCollisionResponseActionTest"}
    )
    public void syncToNms(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (entity.isDead()) return;

        int id = entity.getEntityId();
        Vec3 vel = casStore.getVec(new EntityField(id, "velocity"));
        if (vel == null || vel.equals(Vec3.ZERO)) {
            LOG.fine(() -> "Collision bridge: no velocity in CAS for entity " + id + " — skipping apply");
            return;
        }
        entity.setVelocity(new org.bukkit.util.Vector(vel.x(), vel.y(), vel.z()));
    }

    /**
     * Bulk variant: syncs the detection-phase read for every entity in a
     * collection. Used by the per-tick collision-detection task to push a
     * whole region's worth of entities through the bridge in one call.
     *
     * <p>Conservative {@code @NebulaRW} coverage: same per-entity union as
     * {@link #syncFromNms}.
     *
     * <p>Must be called on the region thread that owns the entities.
     */
    @NebulaRW(
        readEntities       = {"{entityId}.position"},
        writeEntities      = {"{entityId}.position"},
        triggeredEvents    = {"COLLISION_DETECTED"},
        microStep          = MicroStepBehavior.NONE,
        scc                = SccBehavior.SERIALIZED,
        maxRandomCalls     = 0,
        randomInstance     = "NONE",
        mayLoadChunks      = false,
        mayTriggerBlockUpdates = false,
        maySpawnEntities   = false,
        verifiedAt         = "1.21.4",
        verifiedBy         = {"NmsCollisionStateBridgeTest"}
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
     * Bulk variant: applies the response-phase write for every entity in a
     * collection.
     *
     * <p>Conservative {@code @NebulaRW} coverage: same per-entity union as
     * {@link #syncToNms}.
     *
     * <p>Must be called on the region thread that owns the entities.
     */
    @NebulaRW(
        readEntities       = {"{entityId}.velocity"},
        writeEntities      = {"{entityId}.velocity"},
        triggeredEvents    = {"COLLISION_RESPONSE", "ENTITY_MOVED"},
        microStep          = MicroStepBehavior.NONE,
        scc                = SccBehavior.SERIALIZED,
        maxRandomCalls     = 0,
        randomInstance     = "NONE",
        mayLoadChunks      = false,
        mayTriggerBlockUpdates = false,
        maySpawnEntities   = false,
        verifiedAt         = "1.21.4",
        verifiedBy         = {"NmsCollisionStateBridgeTest"}
    )
    public int bulkSyncToNms(Collection<? extends Entity> entities) {
        Objects.requireNonNull(entities, "entities");
        int n = 0;
        for (Entity e : entities) {
            if (e == null || e.isDead()) continue;
            syncToNms(e);
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
        verifiedBy         = {"NmsCollisionStateBridgeTest"}
    )
    public EntityPhysicsState casStore() {
        return casStore;
    }

    /**
     * Locates a live entity in {@code world} by id. Convenience for callers
     * that already have the entity id but not the live reference.
     */
    public static Optional<Entity> findEntityById(World world, int entityId) {
        for (Entity e : world.getEntities()) {
            if (e.getEntityId() == entityId) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }
}
