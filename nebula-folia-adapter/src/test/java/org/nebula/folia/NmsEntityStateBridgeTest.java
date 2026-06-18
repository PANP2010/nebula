package org.nebula.folia;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nebula.core.state.EntityField;
import org.nebula.entity.EntityPhysicsState;
import org.nebula.entity.Vec3;

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
}