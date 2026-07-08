package org.nebula.replay;

import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Turns an <em>unordered</em> set of toggle-source block positions into a
 * <strong>canonically ordered</strong> source list plus its position bindings,
 * ready to assemble a {@link DeterministicToggleSchedule} and
 * {@link LiveLoadToggleDriver} (arch doc §12.1; DG1 Criterion 1 live-load slice).
 *
 * <p>This is the fourth pure, unit-testable slice of the live-load driver, and it
 * closes the last determinism hazard in the pure stack. The three earlier slices
 * are: {@link DeterministicToggleSchedule} ("which sources flip on tick T?"),
 * {@link LiveLoadToggleDriver} ("where is each source and in what order?"), and
 * {@code org.nebula.folia.FoliaToggleApplier} (the one Folia/NMS apply step). What
 * was still missing is the step <em>before</em> the schedule: producing the
 * {@code sourceIds} list itself from whatever the game layer scanned.
 *
 * <h3>Why canonical ordering is correctness-critical (not cosmetic)</h3>
 * {@link DeterministicToggleSchedule} derives each source's flip phase from its
 * <strong>index</strong> in the {@code sourceIds} list. So the toggle stream is a
 * function of source <em>order</em>, not just the set of positions. If the game
 * layer built {@code sourceIds} by iterating {@code componentMap.keySet()} — a
 * {@link java.util.concurrent.ConcurrentHashMap}, whose iteration order is
 * unspecified and can differ run to run — two independent runs of the same world
 * would assign different phases to the same lever and emit
 * <em>legitimately different</em> toggle streams. The zero-diff comparison would
 * then fail for a reason that has nothing to do with the engine: a false
 * divergence, and precisely the invisible-gap class of the B3 key-mismatch wound
 * this whole driver exists to guard against (see PROJECT_STATUS.md, B3).
 *
 * <p>This class removes that hazard by construction: it sorts the input positions
 * with {@link WorldPos}'s total order ({@link WorldPos#compareTo}), so the
 * resulting {@code sourceIds} depend only on the <em>set</em> of positions, never
 * on the order they were enumerated in. Feed the same positions in any order (or
 * with duplicates) and you get the identical schedule stream tick-for-tick.
 *
 * <h3>Source-id format</h3>
 * Each position's stable id is {@code "dim:x,y,z"} — the exact format
 * {@link WorldPos#parse(String)} accepts — so the game layer can resolve a
 * {@code sourceId} back to a {@link WorldPos} without threading a side map, and it
 * matches the {@code dim:x,y,z} tail of {@code RedstoneTaskFactory.taskId}.
 */
public final class CanonicalToggleSources {

    private final List<String> sourceIds;
    private final Map<String, WorldPos> bindings;

    private CanonicalToggleSources(List<String> sourceIds, Map<String, WorldPos> bindings) {
        this.sourceIds = List.copyOf(sourceIds);
        this.bindings = Map.copyOf(bindings);
    }

    /**
     * Builds a canonical plan from a collection of toggle-source positions
     * (typically the scanned lever/button positions). The positions are sorted by
     * {@link WorldPos#compareTo} and de-duplicated, so the result is independent of
     * input iteration order.
     *
     * @param positions the toggle-source block positions; must be non-null and
     *                   contain no nulls (may be empty — see the note on
     *                   {@link #newSchedule}/{@link #newDriver})
     * @throws IllegalArgumentException if {@code positions} is null or contains a null
     */
    public static CanonicalToggleSources of(Collection<WorldPos> positions) {
        if (positions == null) {
            throw new IllegalArgumentException("positions must not be null");
        }
        // TreeSet gives canonical order (WorldPos.compareTo) + de-duplication in one
        // step, so the same set of positions always yields the same ordered list.
        TreeSet<WorldPos> ordered = new TreeSet<>();
        for (WorldPos pos : positions) {
            if (pos == null) {
                throw new IllegalArgumentException("positions must not contain null");
            }
            ordered.add(pos);
        }
        List<String> ids = new ArrayList<>(ordered.size());
        Map<String, WorldPos> binds = new LinkedHashMap<>(ordered.size() * 2);
        for (WorldPos pos : ordered) {
            String id = sourceIdFor(pos);
            ids.add(id);
            binds.put(id, pos);
        }
        return new CanonicalToggleSources(ids, binds);
    }

    /**
     * The stable, position-derived source id for {@code pos}, in the
     * {@code "dim:x,y,z"} format {@link WorldPos#parse(String)} accepts.
     */
    public static String sourceIdFor(WorldPos pos) {
        Objects.requireNonNull(pos, "pos");
        return pos.dimensionId() + ":" + pos.x() + "," + pos.y() + "," + pos.z();
    }

    /** Source ids in canonical (sorted) order. Never contains duplicates. */
    public List<String> sourceIds() {
        return sourceIds;
    }

    /** Unmodifiable sourceId → position bindings for every source in this plan. */
    public Map<String, WorldPos> bindings() {
        return bindings;
    }

    /** Number of distinct toggle sources in this plan. */
    public int size() {
        return sourceIds.size();
    }

    /**
     * Builds a {@link DeterministicToggleSchedule} over these canonical sources.
     *
     * @param seed   determinism seed
     * @param period ticks between successive flips of a single source (≥ 1)
     * @throws IllegalArgumentException if this plan is empty (the schedule requires
     *                                  at least one source) or {@code period < 1}
     */
    public DeterministicToggleSchedule newSchedule(long seed, int period) {
        return new DeterministicToggleSchedule(sourceIds, seed, period);
    }

    /**
     * Builds a fully-wired {@link LiveLoadToggleDriver} ready to drive live-load
     * captures: canonical schedule + these bindings + the given applier. Because
     * the bindings are derived from the same positions as the sourceIds, every
     * schedule source is guaranteed bound, so the driver's fail-fast never trips
     * from this path.
     *
     * @param seed    determinism seed
     * @param period  ticks between successive flips of a single source (≥ 1)
     * @param applier the game-layer apply seam (e.g.
     *                {@code org.nebula.folia.FoliaToggleApplier}); may be null for
     *                resolve-only/dry-run use
     * @throws IllegalArgumentException if this plan is empty or {@code period < 1}
     */
    public LiveLoadToggleDriver newDriver(long seed, int period, ToggleApplier applier) {
        return new LiveLoadToggleDriver(newSchedule(seed, period), bindings, applier);
    }
}
