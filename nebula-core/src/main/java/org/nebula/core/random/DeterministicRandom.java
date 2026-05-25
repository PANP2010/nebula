package org.nebula.core.random;

import java.util.Random;

/**
 * Deterministic random with call-count tracking (arch doc §11.2).
 *
 * <p>Wraps a seeded {@link Random} and counts every call to a random-generating method.
 * This count is used to compare actual vs. declared random consumption, and to detect
 * when a budget re-execution is needed.
 */
public final class DeterministicRandom {

    private long seed;
    private Random random;
    private int callsMade;

    public DeterministicRandom(long seed) {
        this.seed = seed;
        this.random = new Random(seed);
    }

    // ── Seeded generation ────────────────────────────────────────────────────

    public int nextInt() {
        callsMade++;
        return random.nextInt();
    }

    public int nextInt(int bound) {
        callsMade++;
        return random.nextInt(bound);
    }

    public long nextLong() {
        callsMade++;
        return random.nextLong();
    }

    public double nextDouble() {
        callsMade++;
        return random.nextDouble();
    }

    public float nextFloat() {
        callsMade++;
        return random.nextFloat();
    }

    public boolean nextBoolean() {
        callsMade++;
        return random.nextBoolean();
    }

    public void nextBytes(byte[] bytes) {
        callsMade++;
        random.nextBytes(bytes);
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    /** Resets the random to a new seed and zeroes the call counter. */
    public void reset(long newSeed) {
        this.seed = newSeed;
        this.random = new Random(newSeed);
        this.callsMade = 0;
    }

    /** Returns how many random calls have been made since the last reset. */
    public int callsMade() {
        return callsMade;
    }

    /** Current seed (as of last reset). */
    public long seed() {
        return seed;
    }
}
