package org.nebula.folia;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.nebula.core.state.EntityField;
import org.nebula.entity.EntityPhysicsState;
import org.nebula.entity.Vec3;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Bridge between Nebula's {@link EntityPhysicsState} CAS store and real Bukkit
 * entity state through the Folia 26.1.2 API. All reads/writes must occur on the
 * region thread that owns the target entity — the caller is responsible for
 * ensuring this via {@link FoliaRegionBridge#ownsCurrentRegion}.
 *
 * <p>Entity lookup is by {@code entityId} (Bukkit's {@link Entity#getEntityId()})
 * stored in {@link EntityField#entityId()}. The bridge does not maintain its own
 * entity registry — callers provide the entity reference or lookup mechanism.
 *
 * <p>Read path: fetch {@link Entity#getLocation()} and {@link Entity#getVelocity()},
 * convert to {@link Vec3}, commit to CAS store.
 *
 * <p>Write path: convert {@link Vec3} to Bukkit {@link Location}/{@link Vector},
 * call {@link Entity#teleport(Location)} or {@link Entity#setVelocity(Vector)}.
 *
 * <p>Currently binds only position and velocity. Other fields (health, fire ticks,
 * etc.) will be added in subsequent iterations.
 */
public final class NmsEntityStateBridge {

    private static final Logger LOG = Logger.getLogger(NmsEntityStateBridge.class.getName());

    private final EntityPhysicsState casStore;

    public NmsEntityStateBridge(EntityPhysicsState casStore) {
        this.casStore = Objects.requireNonNull(casStore, "casStore");
    }

    // ── Entity lookup helpers ──────────────────────────────────────────────────

    /**
     * Finds an entity by its Bukkit {@code entityId} in the given world.
     * This is O(n) over all loaded entities — callers should cache entity
     * references when possible.
     *
     * <p>Must be called on the region thread that owns the entity's chunk.
     */
    public static Optional<Entity> findEntityById(World world, int entityId) {
        for (Entity e : world.getEntities()) {
            if (e.getEntityId() == entityId) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    /**
     * Finds an entity by its UUID in the given world.
     * More efficient than by-id when the UUID is known.
     *
     * <p>Must be called on the region thread that owns the entity's chunk.
     */
    public static Optional<Entity> findEntityByUuid(World world, UUID uuid) {
        return Optional.ofNullable(world.getEntity(uuid));
    }

    // ── Read path ───────────────────────────────────────────────────────────────

    /**
     * Reads position and velocity from the real entity and commits to the CAS store.
     * Returns the version stamp for stale-read detection.
     *
     * <p>Must be called on the region thread that owns the entity.
     */
    public long syncPositionFromNms(Entity entity) {
        Objects.requireNonNull(entity, "entity");

        Location loc = entity.getLocation();
        Vec3 position = new Vec3(loc.getX(), loc.getY(), loc.getZ());

        EntityField field = new EntityField(entity.getEntityId(), "position");
        long expectedVersion = casStore.getVersion(field);
        casStore.casCommit(field, expectedVersion, position);

        return casStore.getVersion(field);
    }

    public long syncVelocityFromNms(Entity entity) {
        Objects.requireNonNull(entity, "entity");

        Vector vel = entity.getVelocity();
        Vec3 velocity = new Vec3(vel.getX(), vel.getY(), vel.getZ());

        EntityField field = new EntityField(entity.getEntityId(), "velocity");
        long expectedVersion = casStore.getVersion(field);
        casStore.casCommit(field, expectedVersion, velocity);

        return casStore.getVersion(field);
    }

    /**
     * Convenience: sync both position and velocity in one call.
     */
    public void syncPhysicsFromNms(Entity entity) {
        syncPositionFromNms(entity);
        syncVelocityFromNms(entity);
    }

    // ── Write path ──────────────────────────────────────────────────────────────

    /**
     * Teleports the entity to the position stored in the CAS store.
     * Returns true if the teleport succeeded.
     *
     * <p>Must be called on the region thread that owns both the current
     * entity location and the target location.
     */
    public boolean syncPositionToNms(Entity entity, World world) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(world, "world");

        EntityField field = new EntityField(entity.getEntityId(), "position");
        Vec3 position = casStore.getVec(field);

        if (position.equals(Vec3.ZERO)) {
            LOG.fine(() -> "No position in CAS store for entity " + entity.getEntityId());
            return false;
        }

        Location target = new Location(world, position.x(), position.y(), position.z());
        boolean success = entity.teleport(target);

        if (success) {
            LOG.fine(() -> "Teleported entity " + entity.getEntityId() + " to " + position);
        } else {
            LOG.warning(() -> "Teleport failed for entity " + entity.getEntityId());
        }
        return success;
    }

    /**
     * Sets the entity's velocity from the CAS store.
     *
     * <p>Must be called on the region thread that owns the entity.
     */
    public void syncVelocityToNms(Entity entity) {
        Objects.requireNonNull(entity, "entity");

        EntityField field = new EntityField(entity.getEntityId(), "velocity");
        Vec3 velocity = casStore.getVec(field);

        entity.setVelocity(new Vector(velocity.x(), velocity.y(), velocity.z()));
        LOG.fine(() -> "Set velocity " + velocity + " for entity " + entity.getEntityId());
    }

    /**
     * Convenience: sync both position and velocity to NMS in one call.
     */
    public void syncPhysicsToNms(Entity entity, World world) {
        syncPositionToNms(entity, world);
        syncVelocityToNms(entity);
    }

    /**
     * Writes back ONLY the vertical (Y) component of the CAS-stored position and
     * velocity, leaving the entity's live X/Z authoritative to Folia. This is the
     * honest write-back scope established by the divergence finding (B8 C1,
     * 2026-07-10): {@link org.nebula.entity.actions.EntityMoveAction}'s Y matches Folia
     * on the vertical path, but its horizontal X/Z drift is STOCHASTIC AI pathing a
     * deterministic mirror cannot reproduce. See
     * {@link org.nebula.entity.VerticalWriteBackGate} for the arming predicate the
     * caller must satisfy first.
     *
     * <p>The teleport target keeps the entity's <em>current</em> live X/Z (read fresh
     * from {@link Entity#getLocation()}) and substitutes only the CAS Y, so a wandering
     * mob's horizontal position is never perturbed. Likewise only {@code velocity.y}
     * is replaced; the live {@code velocity.x/z} are preserved.
     *
     * <p>Must be called on the region thread that owns the entity.
     */
    public boolean syncVerticalPhysicsToNms(Entity entity, World world) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(world, "world");

        EntityField posField = new EntityField(entity.getEntityId(), "position");
        Vec3 casPos = casStore.getVec(posField);
        if (casPos.equals(Vec3.ZERO)) {
            LOG.fine(() -> "No position in CAS store for entity " + entity.getEntityId());
            return false;
        }

        // Keep Folia's live X/Z; overwrite only Y with the DAG's computed value.
        Location live = entity.getLocation();
        Location target = new Location(world, live.getX(), casPos.y(), live.getZ(),
            live.getYaw(), live.getPitch());
        boolean success = entity.teleport(target);

        if (success) {
            // Preserve the live horizontal velocity; mirror only the vertical.
            Vector liveVel = entity.getVelocity();
            EntityField velField = new EntityField(entity.getEntityId(), "velocity");
            Vec3 casVel = casStore.getVec(velField);
            entity.setVelocity(new Vector(liveVel.getX(), casVel.y(), liveVel.getZ()));
            LOG.fine(() -> "Vertical write-back for entity " + entity.getEntityId()
                + ": y=" + casPos.y() + " vy=" + casVel.y());
        } else {
            LOG.warning(() -> "Vertical write-back teleport failed for entity "
                + entity.getEntityId());
        }
        return success;
    }

    /**
     * Returns the CAS store this bridge is bound to.
     */
    public EntityPhysicsState casStore() {
        return casStore;
    }
}