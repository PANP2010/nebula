package org.nebula.folia;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.nebula.annotations.MicroStepBehavior;
import org.nebula.annotations.NebulaRW;
import org.nebula.annotations.SccBehavior;
import org.nebula.core.math.Vec3;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.EntityPhysicsState;
import org.nebula.entity.ExplosionSnapshot;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Bridge between Nebula's explosion subsystem and the live Bukkit explosion
 * surface. Phase 0's {@code ExplosionTaskFactory} operates on Bukkit's
 * pre-computed {@code EntityExplodeEvent.blockList()}; this bridge is the
 * runtime-classpath landing pad for that subsystem's {@code @NebulaRW}
 * declarations and gives the coverage dashboard (P1.5.3b) a real inventory
 * to count against.
 *
 * <p>The annotated RW-set covers the explosion's two main data axes:
 * <ul>
 *   <li><b>Block-destroy footprint</b> — the per-ray block read the
 *       {@code ExplosionActions.rayTrace} action takes (a sampled subset of
 *       the block list, conservative coverage).</li>
 *   <li><b>Affected-entity footprint</b> — every entity in the blast radius
 *       gets its {@code position}/{@code health}/{@code velocity} read, and
 *       {@code health}/{@code velocity} written by the damage math.</li>
 * </ul>
 *
 * <p>Both axes are declared in their conservative (worst-case) form so the
 * runtime guard treats any explosion task touching one of these positions/
 * entities as depending on this bridge's sync — mirroring the
 * conservative-coverage design of {@link NmsBlockEntityStateBridge}.
 *
 * <p>Like the other bridges, this is the runtime classpath target for
 * {@code @NebulaRW}; NMS itself is not on the runtime classpath, so the
 * annotations here serve the
 * {@link org.nebula.maintenance.BridgeAnnotationScanner} inventory
 * (DG3 Component C, P1.5.3b).
 */
public final class NmsExplosionStateBridge {

    private static final Logger LOG = Logger.getLogger(NmsExplosionStateBridge.class.getName());

    private final EntityPhysicsState entityStore;
    private final Map<WorldPos, ExplosionSnapshot> explosionStore = new ConcurrentHashMap<>();

    public NmsExplosionStateBridge(EntityPhysicsState entityStore) {
        this.entityStore = Objects.requireNonNull(entityStore, "entityStore");
    }

    /**
     * Reads the blast radius's authoritative block list from a
     * {@code EntityExplodeEvent} (already computed by Folia) and commits each
     * affected block to the explosion CAS store. This is the read-path
     * counterpart to {@code ExplosionTaskFactory}'s {@code rayTrace} action —
     * it gives the DAG the same block coordinates the action will later
     * consume.
     *
     * <p>The annotated RW-set lists the conservative block footprint: the
     * source position plus the per-block block-write side. The runtime guard
     * treats any explosion-block task at one of these positions as depending
     * on this sync.
     *
     * <p>Must be called on the region thread that owns the source position.
     */
    @NebulaRW(
        readBlocks         = {"{source}", "{source.north}", "{source.south}",
                              "{source.east}", "{source.west}", "{source.up}", "{source.down}"},
        writeBlocks        = {"{source}", "{source.north}", "{source.south}",
                              "{source.east}", "{source.west}", "{source.up}", "{source.down}"},
        triggeredEvents    = {"BLOCK_UPDATE", "EXPLOSION_STARTED"},
        microStep          = MicroStepBehavior.NONE,
        scc                = SccBehavior.AUTO,
        maxRandomCalls     = 0,
        randomInstance     = "NONE",
        mayLoadChunks      = false,
        mayTriggerBlockUpdates = true,
        maySpawnEntities   = true,
        verifiedAt         = "1.21.4",
        verifiedBy         = {"ExplosionTaskFactoryTest"}
    )
    public int syncFromNms(World world, WorldPos source, Collection<Block> affectedBlocks) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(affectedBlocks, "affectedBlocks");

        int n = 0;
        for (Block b : affectedBlocks) {
            if (b == null || b.getWorld() == null || !b.getWorld().equals(world)) continue;
            int dimId = world.getUID().hashCode();
            WorldPos pos = new WorldPos(dimId,
                b.getX(), b.getY(), b.getZ());
            explosionStore.put(pos, new ExplosionSnapshot(source, 4.0f, -1L,
                Set.of(pos), java.util.List.of()));
            n++;
        }
        int finalN = n;
        LOG.fine(() -> "Explosion bridge: synced " + finalN + " affected blocks for source " + source);
        return finalN;
    }

    /**
     * Reads the affected-entity list (entities in blast radius) and commits
     * their position/health to the entity CAS store. This is the entity-side
     * read path; the damage math in {@code ExplosionActions.entityDamage}
     * consumes the committed position/health and writes the new health +
     * velocity.
     *
     * <p>The annotated RW-set is the conservative entity-field union: every
     * affected entity's {@code position}, {@code health}, and {@code velocity}
     * field. The runtime guard treats any entity-damage task touching one of
     * these fields as depending on this sync.
     *
     * <p>Must be called on the region thread that owns the affected entities.
     */
    @NebulaRW(
        readEntities       = {"{entityId}.position", "{entityId}.health", "{entityId}.velocity"},
        writeEntities      = {"{entityId}.position", "{entityId}.health", "{entityId}.velocity"},
        triggeredEvents    = {"ENTITY_DAMAGED", "ENTITY_MOVED"},
        microStep          = MicroStepBehavior.NONE,
        scc                = SccBehavior.SERIALIZED,
        maxRandomCalls     = 5,
        randomInstance     = "WORLD_RANDOM",
        mayLoadChunks      = false,
        mayTriggerBlockUpdates = false,
        maySpawnEntities   = false,
        verifiedAt         = "1.21.4",
        verifiedBy         = {"ExplosionTaskFactoryTest"}
    )
    public int syncEntitiesFromNms(Collection<Entity> affectedEntities) {
        Objects.requireNonNull(affectedEntities, "affectedEntities");

        int n = 0;
        for (Entity e : affectedEntities) {
            if (e == null || e.isDead()) continue;
            int id = e.getEntityId();
            Location loc = e.getLocation();
            double health = e instanceof org.bukkit.entity.LivingEntity le ? le.getHealth() : 0.0;

            entityStore.casCommit(new EntityField(id, "position"),
                entityStore.getVersion(new EntityField(id, "position")),
                new Vec3(loc.getX(), loc.getY(), loc.getZ()));
            entityStore.casCommit(new EntityField(id, "health"),
                entityStore.getVersion(new EntityField(id, "health")), health);
            n++;
        }
        int finalN = n;
        LOG.fine(() -> "Explosion bridge: synced " + finalN + " affected entities");
        return finalN;
    }

    /**
     * Writes the explosion-damaged entity state back to NMS. Consumes the
     * damage math's outputs ({@code health} and {@code velocity}) and pushes
     * them onto the live entities.
     *
     * <p>Must be called on the region thread that owns the affected entities.
     */
    @NebulaRW(
        readEntities       = {"{entityId}.health", "{entityId}.velocity"},
        writeEntities      = {"{entityId}.health", "{entityId}.velocity"},
        triggeredEvents    = {"ENTITY_DAMAGED", "ENTITY_MOVED"},
        microStep          = MicroStepBehavior.NONE,
        scc                = SccBehavior.SERIALIZED,
        maxRandomCalls     = 0,
        randomInstance     = "NONE",
        mayLoadChunks      = false,
        mayTriggerBlockUpdates = false,
        maySpawnEntities   = false,
        verifiedAt         = "1.21.4",
        verifiedBy         = {"ExplosionTaskFactoryTest"}
    )
    public void syncEntitiesToNms(Collection<Entity> affectedEntities) {
        Objects.requireNonNull(affectedEntities, "affectedEntities");

        int n = 0;
        for (Entity e : affectedEntities) {
            if (e == null || e.isDead()) continue;
            int id = e.getEntityId();
            EntityField healthField = new EntityField(id, "health");
            EntityField velField = new EntityField(id, "velocity");

            if (e instanceof org.bukkit.entity.LivingEntity le) {
                double newHealth = entityStore.getVec(healthField) != null
                    ? entityStore.getVec(healthField).x() : le.getHealth();
                le.setHealth(Math.max(0.0, newHealth));
            }
            Vec3 vel = entityStore.getVec(velField);
            if (vel != null) {
                e.setVelocity(new org.bukkit.util.Vector(vel.x(), vel.y(), vel.z()));
            }
            n++;
        }
        int finalN = n;
        LOG.fine(() -> "Explosion bridge: wrote " + finalN + " damaged entities to NMS");
    }

    /**
     * Returns the entity CAS store this bridge is bound to.
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
        verifiedBy         = {"NmsExplosionStateBridgeTest"}
    )
    public EntityPhysicsState entityStore() {
        return entityStore;
    }

    /**
     * Returns a read-only view of the explosion CAS store. Used by the DAG
     * explosion tasks to look up the affected block coordinates.
     */
    public Map<WorldPos, ExplosionSnapshot> explosionSnapshots() {
        return Map.copyOf(explosionStore);
    }

    /**
     * Locates an entity in {@code world} by id. Convenience for callers that
     * already have the entity id but not the live reference.
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
