package org.nebula.core.random;

import org.nebula.core.state.RandomInstance;

/**
 * Derives per-task {@link DeterministicRandom} streams from a stable coordinate
 * {@code (worldSeed, tick, entityId, instance)} (arch doc §11.1 "分层随机").
 *
 * <p>The central determinism property: a task's random stream depends only on
 * its logical coordinate, never on execution order or thread. Two runs of the
 * same tick — serial or parallel, in any layer order — derive the identical
 * seed for a given entity, so RNG-consuming tasks produce identical results.
 * This is what makes parallel entity AI replay-deterministic.
 *
 * <p>Seeds are mixed with a SplitMix64 finalizer (good avalanche), so adjacent
 * coordinates such as {@code (tick, id)} and {@code (tick, id+1)} yield
 * uncorrelated streams rather than near-identical ones.
 */
public final class LayeredRandomSource {

    private final long worldSeed;

    public LayeredRandomSource(long worldSeed) {
        this.worldSeed = worldSeed;
    }

    /**
     * Returns a fresh {@link DeterministicRandom} seeded for the given task
     * coordinate. Call-count starts at zero.
     *
     * @param tick      the game tick being executed
     * @param entityId  the entity (or block / world) the stream belongs to
     * @param instance  which logical RNG instance (entity, world, block, …)
     */
    public DeterministicRandom forTask(long tick, long entityId, RandomInstance instance) {
        return new DeterministicRandom(deriveSeed(tick, entityId, instance));
    }

    /** Exposes the derived seed for a coordinate (for tests / inspection). */
    public long deriveSeed(long tick, long entityId, RandomInstance instance) {
        long h = worldSeed;
        h = mix(h ^ 0x9E3779B97F4A7C15L);
        h = mix(h ^ tick);
        h = mix(h ^ entityId);
        h = mix(h ^ (instance.ordinal() + 0x1L));
        return h;
    }

    public long worldSeed() {
        return worldSeed;
    }

    /** SplitMix64 finalizer — strong avalanche mixing of a 64-bit value. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
