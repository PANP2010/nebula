package org.nebula.core.vap;

/**
 * Concurrency strategies for {@link ManagedState} fields (arch doc §13.3).
 */
public enum ConcurrencyStrategy {

    /**
     * Multi-Version Concurrency Control. Each modification creates a new version
     * (copy-on-write). Reads use a consistent snapshot. Concurrent writes are
     * resolved via the declared merge function.
     *
     * <p>Best for: Map, List, and other container types.
     */
    MVCC,

    /**
     * Atomic operations via AtomicReference/AtomicInteger. Direct CAS-based
     * updates with no versioning overhead.
     *
     * <p>Best for: counters, flags, and simple scalar values.
     */
    ATOMIC,

    /**
     * Read-write lock. VAP automatically inserts ReentrantReadWriteLock around
     * field access. Multiple concurrent readers allowed, exclusive writer access.
     *
     * <p>Best for: complex custom data structures that don't fit MVCC or ATOMIC.
     */
    LOCK
}
