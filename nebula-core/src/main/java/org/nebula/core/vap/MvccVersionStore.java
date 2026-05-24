package org.nebula.core.vap;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BinaryOperator;
import java.util.function.UnaryOperator;

/**
 * MVCC version store for a single {@link ManagedState} field (arch doc §13.3).
 *
 * <p>Provides snapshot-isolated reads and copy-on-write semantics for concurrent
 * plugin access. When two writers produce conflicting versions within the same
 * tick, the configured merge function resolves the conflict.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>At tick start, call {@link #snapshot()} to freeze the current version.</li>
 *   <li>During the tick, writers call {@link #update(UnaryOperator)} — each call
 *       atomically produces a new version.</li>
 *   <li>At tick end, if multiple writes occurred, call {@link #merge()} to
 *       collapse concurrent branches using the merge function.</li>
 * </ol>
 *
 * @param <T> the managed field type
 */
public final class MvccVersionStore<T> {

    private final AtomicReference<T> current;
    private final BinaryOperator<T> mergeFunction;
    private volatile T tickSnapshot;

    /**
     * @param initial        initial value
     * @param mergeFunction  merge function for conflict resolution; if null, last-writer-wins
     */
    public MvccVersionStore(T initial, BinaryOperator<T> mergeFunction) {
        this.current = new AtomicReference<>(Objects.requireNonNull(initial));
        this.mergeFunction = mergeFunction != null ? mergeFunction : (a, b) -> b;
        this.tickSnapshot = initial;
    }

    public MvccVersionStore(T initial) {
        this(initial, null);
    }

    /**
     * Takes a snapshot of the current value for consistent reads during this tick.
     * Should be called once at tick start.
     */
    public void snapshot() {
        tickSnapshot = current.get();
    }

    /**
     * Returns the tick-start snapshot (consistent read).
     */
    public T read() {
        return tickSnapshot;
    }

    /**
     * Returns the live (latest written) value. Use with caution — not snapshot-isolated.
     */
    public T readLatest() {
        return current.get();
    }

    /**
     * Atomically updates the current value using a transform function.
     * The transform receives the latest value (not the snapshot).
     *
     * @param transform function to apply
     * @return the new value after transformation
     */
    public T update(UnaryOperator<T> transform) {
        return current.updateAndGet(transform);
    }

    /**
     * Sets the value directly (last-writer-wins semantics).
     */
    public void set(T value) {
        current.set(value);
    }

    /**
     * Merges two divergent values using the configured merge function.
     * Used when two plugin tasks wrote the same field concurrently within one tick.
     *
     * @param v1 first version (e.g. from plugin A)
     * @param v2 second version (e.g. from plugin B)
     * @return merged result
     */
    public T merge(T v1, T v2) {
        T merged = mergeFunction.apply(v1, v2);
        current.set(merged);
        return merged;
    }

    /**
     * Returns the merge function.
     */
    public BinaryOperator<T> mergeFunction() {
        return mergeFunction;
    }
}
