package org.nebula.plugin;

import org.nebula.core.state.WorldPos;
import org.nebula.replay.CanonicalToggleSources;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A thread-safe set of the world's <em>manual toggle-source</em> positions
 * (levers/buttons), tracked separately from {@code componentMap} because the
 * scanner collapses those materials into {@link org.nebula.redstone.RedstoneComponentType#REDSTONE_TORCH}
 * and so loses their identity (see {@link ToggleSourceClassifier}).
 *
 * <p>This is the game-layer half of the "first blocker" the live-load driver
 * needed cleared: the scanner registers a redstone component AND, when the block
 * is a lever/button, records its position here too. The live-load driver then
 * builds its canonical source list from {@link #snapshot()} rather than from
 * {@code componentMap.keySet()} — which is essential, because
 * {@link CanonicalToggleSources} derives each source's flip phase from its index
 * in a canonical (sorted) order, and only a set of true toggle sources (not every
 * torch-shaped component) can produce a faithful driven toggle stream.
 *
 * <p>Registration happens on scan / block-place threads and the snapshot is read
 * when a capture is armed, so the backing set is a concurrent set. This class
 * does <strong>not</strong> participate in the per-tick pipeline — nothing reads
 * it during {@code executeOwnedDag}; it is pure bookkeeping consumed only when a
 * driver is assembled. That keeps it fully unit-testable and off the hot path.
 */
public final class ToggleSourceRegistry {

    // Concurrent set (backed by a ConcurrentHashMap) — registration runs on
    // region/scan threads. WorldPos is a value type, so membership is by value.
    private final Set<WorldPos> sources = ConcurrentHashMap.newKeySet();

    /**
     * Records {@code pos} as a manual toggle source. Idempotent — registering the
     * same position twice leaves the set unchanged.
     *
     * @param pos the lever/button position; must not be null
     * @return true if this call added a new position, false if it was already present
     */
    public boolean register(WorldPos pos) {
        if (pos == null) {
            throw new IllegalArgumentException("pos must not be null");
        }
        return sources.add(pos);
    }

    /**
     * Removes {@code pos} from the toggle-source set (e.g. the lever was broken).
     *
     * @param pos the position to remove; a null or absent position is a no-op
     * @return true if a position was removed
     */
    public boolean unregister(WorldPos pos) {
        return pos != null && sources.remove(pos);
    }

    /** Whether {@code pos} is currently tracked as a toggle source. */
    public boolean contains(WorldPos pos) {
        return pos != null && sources.contains(pos);
    }

    /** Number of tracked toggle sources. */
    public int size() {
        return sources.size();
    }

    /**
     * An unmodifiable point-in-time view of the tracked toggle-source positions.
     * The returned set is a copy, so it is stable even if registration continues
     * concurrently — suitable for feeding {@link #canonical()} or building a driver.
     */
    public Set<WorldPos> snapshot() {
        return Collections.unmodifiableSet(Set.copyOf(sources));
    }

    /**
     * Builds a {@link CanonicalToggleSources} plan from the current toggle-source
     * set. Because {@code CanonicalToggleSources.of} sorts + de-duplicates by
     * {@link WorldPos#compareTo}, the resulting source ordering is a function of
     * the SET of positions only — never of registration/enumeration order — so two
     * runs of the same world yield the identical schedule stream (the determinism
     * property this whole path exists to guarantee).
     */
    public CanonicalToggleSources canonical() {
        return CanonicalToggleSources.of(snapshot());
    }
}
