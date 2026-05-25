package org.nebula.core.random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeterministicRandomTest {

    @Test
    void sameSeedProducesSameSequence() {
        DeterministicRandom r1 = new DeterministicRandom(42L);
        DeterministicRandom r2 = new DeterministicRandom(42L);
        for (int i = 0; i < 20; i++) {
            assertEquals(r1.nextInt(), r2.nextInt());
        }
    }

    @Test
    void callCountTracked() {
        DeterministicRandom r = new DeterministicRandom(1L);
        assertEquals(0, r.callsMade());
        r.nextInt(); r.nextInt(); r.nextDouble();
        assertEquals(3, r.callsMade());
    }

    @Test
    void resetRestartsSequence() {
        DeterministicRandom r = new DeterministicRandom(7L);
        int first = r.nextInt();
        r.reset(7L);
        assertEquals(0, r.callsMade());
        assertEquals(first, r.nextInt());
    }

    @Test
    void differentSeedsDifferentSequences() {
        DeterministicRandom r1 = new DeterministicRandom(100L);
        DeterministicRandom r2 = new DeterministicRandom(200L);
        boolean anyDiff = false;
        for (int i = 0; i < 10; i++) {
            if (r1.nextInt() != r2.nextInt()) { anyDiff = true; break; }
        }
        assertTrue(anyDiff, "Different seeds should produce different sequences");
    }

    @Test
    void allMethodsIncrementCallCount() {
        DeterministicRandom r = new DeterministicRandom(0L);
        r.nextInt();
        r.nextInt(10);
        r.nextLong();
        r.nextDouble();
        r.nextFloat();
        r.nextBoolean();
        r.nextBytes(new byte[4]);
        assertEquals(7, r.callsMade());
    }

    @Test
    void seedAccessible() {
        DeterministicRandom r = new DeterministicRandom(999L);
        assertEquals(999L, r.seed());
        r.reset(123L);
        assertEquals(123L, r.seed());
    }
}
