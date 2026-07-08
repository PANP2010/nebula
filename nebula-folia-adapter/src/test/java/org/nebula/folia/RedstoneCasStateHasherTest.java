package org.nebula.folia;

import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;
import org.nebula.redstone.RedstoneWorldState;

import java.lang.reflect.Proxy;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests {@link RedstoneCasStateHasher}, which reads redstone state from the
 * thread-safe {@link RedstoneWorldState} CAS store rather than from live NMS
 * blocks.  A minimal {@link World} proxy supplies only {@code getName()}.
 */
class RedstoneCasStateHasherTest {

    private static final int DIM = 0;

    private RedstoneWorldState state;
    private RedstoneCasStateHasher hasher;
    private World world;

    @BeforeEach
    void setUp() {
        state = new RedstoneWorldState();
        hasher = new RedstoneCasStateHasher(state);
        world = (World) Proxy.newProxyInstance(
            World.class.getClassLoader(), new Class<?>[]{World.class},
            (p, m, a) -> "getName".equals(m.getName()) ? "test_world"
                : defaultFor(m.getReturnType()));
    }

    private static Object defaultFor(Class<?> r) {
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        if (r == double.class) return 0.0;
        return null;
    }

    @Test
    void hashIsDeterministicForSameState() {
        WorldPos pos = new WorldPos(DIM, 10, 64, 20);
        state.putPowerLevel(pos, 7);
        hasher.trackPosition(pos);

        assertArrayEquals(hasher.hashState(world, 100), hasher.hashState(world, 100),
            "Same state should produce same hash");
    }

    @Test
    void differentTickProducesDifferentHash() {
        WorldPos pos = new WorldPos(DIM, 10, 64, 20);
        state.putPowerLevel(pos, 7);
        hasher.trackPosition(pos);

        assertNotEquals(hex(hasher.hashState(world, 100)), hex(hasher.hashState(world, 200)),
            "Different tick should produce different hash");
    }

    @Test
    void differentPowerProducesDifferentHash() {
        WorldPos pos = new WorldPos(DIM, 10, 64, 20);
        hasher.trackPosition(pos);

        state.putPowerLevel(pos, 7);
        byte[] hash7 = hasher.hashState(world, 100);
        state.putPowerLevel(pos, 3);
        byte[] hash3 = hasher.hashState(world, 100);

        assertNotEquals(hex(hash7), hex(hash3),
            "Different power should produce different hash");
    }

    @Test
    void trackingAndUntrackingChangesHash() {
        WorldPos pos1 = new WorldPos(DIM, 10, 64, 20);
        WorldPos pos2 = new WorldPos(DIM, 20, 64, 30);
        state.putPowerLevel(pos1, 5);
        state.putPowerLevel(pos2, 5);

        hasher.trackPosition(pos1);
        byte[] hash1 = hasher.hashState(world, 100);

        hasher.trackPosition(pos2);
        byte[] hash2 = hasher.hashState(world, 100);
        assertNotEquals(hex(hash1), hex(hash2),
            "More tracked positions should change hash");

        hasher.untrackPosition(pos2);
        assertArrayEquals(hash1, hasher.hashState(world, 100),
            "Untracking should restore original hash");
    }

    @Test
    void unsetPositionHashesAsPowerZero() {
        WorldPos unset = new WorldPos(DIM, 1, 64, 1);
        WorldPos zero = new WorldPos(DIM, 1, 64, 1);
        hasher.trackPosition(unset);
        byte[] hUnset = hasher.hashState(world, 100);

        state.putPowerLevel(zero, 0);
        byte[] hZero = hasher.hashState(world, 100);

        assertArrayEquals(hUnset, hZero,
            "An unset position (-1) must hash identically to explicit power 0");
    }

    @Test
    void emptyTrackedSetProducesValidHash() {
        assertEquals(32, hasher.hashState(world, 100).length,
            "SHA-256 should produce 32 bytes even for empty tracked set");
    }

    @Test
    void orderingIsIndependentOfInsertion() {
        WorldPos a = new WorldPos(DIM, 1, 64, 1);
        WorldPos b = new WorldPos(DIM, 2, 64, 2);
        state.putPowerLevel(a, 4);
        state.putPowerLevel(b, 9);

        RedstoneCasStateHasher h1 = new RedstoneCasStateHasher(state);
        h1.trackPosition(a);
        h1.trackPosition(b);

        RedstoneCasStateHasher h2 = new RedstoneCasStateHasher(state);
        h2.trackPosition(b);
        h2.trackPosition(a);

        assertArrayEquals(h1.hashState(world, 100), h2.hashState(world, 100),
            "Hash must be independent of track insertion order (sorted internally)");
    }

    private static String hex(byte[] b) {
        return HexFormat.of().formatHex(b);
    }
}
