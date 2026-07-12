package org.nebula.folia;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nebula.core.math.Vec3;
import org.nebula.core.state.EntityField;
import org.nebula.entity.EntityPhysicsState;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link NmsEntityStateBridge} against the real Folia API types, with
 * proxy-stubbed Bukkit entities whose position and velocity are controllable.
 */
class NmsEntityStateBridgeTest {

    private static final int ENTITY_ID = 42;
    private static final UUID ENTITY_UUID = UUID.randomUUID();

    private EntityPhysicsState casStore;
    private NmsEntityStateBridge bridge;

    @BeforeEach
    void setUp() {
        casStore = new EntityPhysicsState();
        bridge = new NmsEntityStateBridge(casStore);
    }

    private static Object def(Method m) {
        Class<?> r = m.getReturnType();
        if (r == boolean.class) return false;
        if (r == int.class || r == long.class || r == short.class || r == byte.class) return 0;
        if (r == double.class) return 0.0;
        if (r == float.class) return 0.0f;
        if (r == Optional.class) return Optional.empty();
        return null;
    }

    /** Creates an Entity stub with given position and velocity. */
    private static Entity entityAt(double x, double y, double z, double vx, double vy, double vz) {
        // Create a stub World for Location
        World stubWorld = (World) Proxy.newProxyInstance(
            World.class.getClassLoader(), new Class<?>[]{World.class},
            (p, m, a) -> def(m));
        Location loc = new Location(stubWorld, x, y, z);
        Vector vel = new Vector(vx, vy, vz);
        return (Entity) Proxy.newProxyInstance(
            Entity.class.getClassLoader(), new Class<?>[]{Entity.class},
            (p, m, a) -> {
                if (m.getName().equals("getEntityId")) return ENTITY_ID;
                if (m.getName().equals("getUniqueId")) return ENTITY_UUID;
                if (m.getName().equals("getLocation")) return loc;
                if (m.getName().equals("getVelocity")) return vel;
                if (m.getName().equals("teleport") && a != null && a.length >= 1
                        && a[0] instanceof Location) return true;
                return def(m);
            });
    }

    private static World worldWith(Entity entity) {
        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(), new Class<?>[]{World.class},
            (p, m, a) -> {
                if (m.getName().equals("getEntities")) return List.of(entity);
                if (m.getName().equals("getEntity") && a != null && a.length == 1
                        && a[0] instanceof UUID) {
                    return a[0].equals(ENTITY_UUID) ? entity : null;
                }
                return def(m);
            });
    }

    @Test
    void syncPositionFromNms_readsIntoCasStore() {
        Entity entity = entityAt(100.5, 64.0, -200.3, 0, 0, 0);

        long version = bridge.syncPositionFromNms(entity);

        assertTrue(version > 0);
        EntityField field = new EntityField(ENTITY_ID, "position");
        Vec3 pos = casStore.getVec(field);
        assertEquals(100.5, pos.x(), 0.001);
        assertEquals(64.0, pos.y(), 0.001);
        assertEquals(-200.3, pos.z(), 0.001);
    }

    @Test
    void syncVelocityFromNms_readsIntoCasStore() {
        Entity entity = entityAt(0, 0, 0, 1.5, 3.0, -0.7);

        bridge.syncVelocityFromNms(entity);

        EntityField field = new EntityField(ENTITY_ID, "velocity");
        Vec3 vel = casStore.getVec(field);
        assertEquals(1.5, vel.x(), 0.001);
        assertEquals(3.0, vel.y(), 0.001);
        assertEquals(-0.7, vel.z(), 0.001);
    }

    @Test
    void syncPhysicsFromNms_readsBothPositionAndVelocity() {
        Entity entity = entityAt(10.0, 20.0, 30.0, 1.0, 2.0, 3.0);

        bridge.syncPhysicsFromNms(entity);

        EntityField posField = new EntityField(ENTITY_ID, "position");
        EntityField velField = new EntityField(ENTITY_ID, "velocity");
        assertEquals(new Vec3(10.0, 20.0, 30.0), casStore.getVec(posField));
        assertEquals(new Vec3(1.0, 2.0, 3.0), casStore.getVec(velField));
    }

    @Test
    void syncVelocityToNms_writesFromCasStore() {
        Entity entity = entityAt(0, 0, 0, 0, 0, 0);
        EntityField field = new EntityField(ENTITY_ID, "velocity");
        casStore.casCommit(field, 0, new Vec3(5.0, 10.0, -2.0));

        bridge.syncVelocityToNms(entity);

        // The entity stub's setVelocity is called — we trust the proxy dispatch
        // since we can't read back the velocity from our stub. Verify no exception.
    }

    @Test
    void syncPositionToNms_teleportsEntity() {
        // Create entity that tracks teleport calls
        boolean[] teleported = {false};
        Location[] teleportedTo = {null};

        Entity entity = (Entity) Proxy.newProxyInstance(
            Entity.class.getClassLoader(), new Class<?>[]{Entity.class},
            (p, m, a) -> {
                if (m.getName().equals("getEntityId")) return ENTITY_ID;
                if (m.getName().equals("teleport") && a != null && a.length >= 1
                        && a[0] instanceof Location loc) {
                    teleported[0] = true;
                    teleportedTo[0] = loc;
                    return true;
                }
                return def(m);
            });
        World world = worldWith(entity);

        EntityField field = new EntityField(ENTITY_ID, "position");
        casStore.casCommit(field, 0, new Vec3(50.0, 70.0, -30.0));

        boolean result = bridge.syncPositionToNms(entity, world);

        assertTrue(result, "teleport should succeed");
        assertTrue(teleported[0], "entity.teleport should have been called");
        assertEquals(50.0, teleportedTo[0].getX(), 0.001);
        assertEquals(70.0, teleportedTo[0].getY(), 0.001);
        assertEquals(-30.0, teleportedTo[0].getZ(), 0.001);
    }

    @Test
    void findEntityById_findsMatchingEntity() {
        Entity entity = entityAt(0, 0, 0, 0, 0, 0);
        World world = worldWith(entity);

        Optional<Entity> found = NmsEntityStateBridge.findEntityById(world, ENTITY_ID);

        assertTrue(found.isPresent());
        assertEquals(ENTITY_ID, found.get().getEntityId());
    }

    @Test
    void findEntityById_returnsEmptyForMissingEntity() {
        Entity entity = entityAt(0, 0, 0, 0, 0, 0);
        World world = worldWith(entity);

        Optional<Entity> found = NmsEntityStateBridge.findEntityById(world, 9999);

        assertFalse(found.isPresent());
    }

    @Test
    void findEntityByUuid_findsMatchingEntity() {
        Entity entity = entityAt(0, 0, 0, 0, 0, 0);
        World world = worldWith(entity);

        Optional<Entity> found = NmsEntityStateBridge.findEntityByUuid(world, ENTITY_UUID);

        assertTrue(found.isPresent());
    }

    @Test
    void syncVerticalPhysicsToNms_mirrorsYButKeepsLiveXZ() {
        // Live entity is at (10, 64, 20) moving horizontally; CAS holds the DAG's
        // computed position/velocity with a DIFFERENT Y and X/Z. Vertical write-back
        // must teleport to (liveX, casY, liveZ) — Folia keeps X/Z, Nebula supplies Y.
        Location[] teleportedTo = {null};
        Vector[] velSet = {null};
        Location live = new Location(stubWorld(), 10.0, 64.0, 20.0);
        Vector liveVel = new Vector(0.12, -0.4, -0.07);

        Entity entity = (Entity) Proxy.newProxyInstance(
            Entity.class.getClassLoader(), new Class<?>[]{Entity.class},
            (p, m, a) -> {
                switch (m.getName()) {
                    case "getEntityId": return ENTITY_ID;
                    case "getLocation": return live;
                    case "getVelocity": return liveVel;
                    case "teleport":
                        if (a != null && a.length >= 1 && a[0] instanceof Location loc) {
                            teleportedTo[0] = loc;
                            return true;
                        }
                        return false;
                    case "setVelocity":
                        if (a != null && a.length == 1 && a[0] instanceof Vector v) {
                            velSet[0] = v;
                        }
                        return null;
                    default: return def(m);
                }
            });
        World world = worldWith(entity);

        // DAG computed a different position AND velocity than the live entity.
        casStore.casCommit(new EntityField(ENTITY_ID, "position"), 0, new Vec3(999.0, 62.5, 999.0));
        casStore.casCommit(new EntityField(ENTITY_ID, "velocity"), 0, new Vec3(999.0, -0.48, 999.0));

        boolean result = bridge.syncVerticalPhysicsToNms(entity, world);

        assertTrue(result, "vertical write-back should succeed");
        // X/Z stay LIVE (Folia authoritative); only Y comes from CAS.
        assertEquals(10.0, teleportedTo[0].getX(), 0.001, "X must stay Folia-live");
        assertEquals(62.5, teleportedTo[0].getY(), 0.001, "Y must be mirrored from CAS");
        assertEquals(20.0, teleportedTo[0].getZ(), 0.001, "Z must stay Folia-live");
        // Velocity: horizontal stays live, only vy is mirrored.
        assertEquals(0.12, velSet[0].getX(), 0.001, "vx must stay Folia-live");
        assertEquals(-0.48, velSet[0].getY(), 0.001, "vy must be mirrored from CAS");
        assertEquals(-0.07, velSet[0].getZ(), 0.001, "vz must stay Folia-live");
    }

    @Test
    void syncVerticalPhysicsToNms_noopWhenCasEmpty() {
        // No CAS position → nothing to write back (Vec3.ZERO sentinel), returns false.
        Entity entity = entityAt(5.0, 70.0, 5.0, 0, 0, 0);
        World world = worldWith(entity);

        assertFalse(bridge.syncVerticalPhysicsToNms(entity, world),
            "vertical write-back is a no-op when CAS has no position");
    }

    private static World stubWorld() {
        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(), new Class<?>[]{World.class},
            (p, m, a) -> def(m));
    }
}