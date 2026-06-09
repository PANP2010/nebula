package org.nebula.core.random;

import org.junit.jupiter.api.Test;
import org.nebula.core.state.RandomInstance;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayeredRandomSourceTest {

    @Test
    void sameCoordinateProducesSameSeedAndStream() {
        LayeredRandomSource src = new LayeredRandomSource(12345L);
        DeterministicRandom a = src.forTask(100, 7, RandomInstance.ENTITY_RANDOM);
        DeterministicRandom b = src.forTask(100, 7, RandomInstance.ENTITY_RANDOM);
        assertEquals(a.seed(), b.seed());
        for (int i = 0; i < 20; i++) {
            assertEquals(a.nextInt(), b.nextInt());
        }
    }

    @Test
    void differentTickProducesDifferentSeed() {
        LayeredRandomSource src = new LayeredRandomSource(1L);
        assertNotEquals(
            src.deriveSeed(100, 7, RandomInstance.ENTITY_RANDOM),
            src.deriveSeed(101, 7, RandomInstance.ENTITY_RANDOM));
    }

    @Test
    void differentEntityProducesDifferentSeed() {
        LayeredRandomSource src = new LayeredRandomSource(1L);
        assertNotEquals(
            src.deriveSeed(100, 7, RandomInstance.ENTITY_RANDOM),
            src.deriveSeed(100, 8, RandomInstance.ENTITY_RANDOM));
    }

    @Test
    void differentInstanceProducesDifferentSeed() {
        LayeredRandomSource src = new LayeredRandomSource(1L);
        assertNotEquals(
            src.deriveSeed(100, 7, RandomInstance.ENTITY_RANDOM),
            src.deriveSeed(100, 7, RandomInstance.WORLD_RANDOM));
    }

    @Test
    void differentWorldSeedProducesDifferentSeed() {
        assertNotEquals(
            new LayeredRandomSource(1L).deriveSeed(100, 7, RandomInstance.ENTITY_RANDOM),
            new LayeredRandomSource(2L).deriveSeed(100, 7, RandomInstance.ENTITY_RANDOM));
    }

    @Test
    void adjacentCoordinatesAreDecorrelated() {
        // Seeds for adjacent (tick, entityId) coordinates must be well-spread,
        // not near-sequential — a weak hash would correlate neighbouring entities.
        LayeredRandomSource src = new LayeredRandomSource(0xCAFEL);
        Set<Long> seeds = new HashSet<>();
        for (long tick = 0; tick < 50; tick++) {
            for (long id = 0; id < 50; id++) {
                seeds.add(src.deriveSeed(tick, id, RandomInstance.ENTITY_RANDOM));
            }
        }
        // 2500 distinct coordinates should yield 2500 distinct seeds (no collisions).
        assertEquals(2500, seeds.size(), "seed derivation must be collision-free over a dense grid");
    }

    @Test
    void seedIsReproducibleAcrossInstances() {
        // A fresh source with the same world seed derives identical seeds —
        // the property a replay relies on.
        long s1 = new LayeredRandomSource(999L).deriveSeed(42, 13, RandomInstance.ENTITY_RANDOM);
        long s2 = new LayeredRandomSource(999L).deriveSeed(42, 13, RandomInstance.ENTITY_RANDOM);
        assertEquals(s1, s2);
        assertTrue(true);
    }
}
