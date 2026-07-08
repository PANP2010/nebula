package org.nebula.replay;

import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the live-load zero-diff input driver (DG1 Criterion 1 live-load
 * slice, arch doc §12.1): it binds each opaque {@link DeterministicToggleSchedule}
 * {@code sourceId} to a concrete {@link WorldPos}, resolves the schedule's actions
 * for a given tick into {@link ResolvedToggle}s, and (optionally) hands each to a
 * {@link ToggleApplier} for region-thread application.
 *
 * <p>This is the second pure, unit-testable slice of the driver. The first slice
 * ({@link DeterministicToggleSchedule}) answered "which sources flip on tick T?";
 * this slice answers "where is each source, and in what order do we apply them?"
 * — everything up to, but not including, the one Folia/NMS step, which is the
 * injected {@link ToggleApplier}. Given the same seed, source list, and bindings,
 * two independent runs resolve the <strong>identical</strong> toggle stream
 * tick-for-tick, so any run-to-run state divergence is attributable to the engine,
 * not the input.
 *
 * <h3>Fail-fast binding (why every source must resolve)</h3>
 * If a {@code sourceId} in the schedule has no position binding, this driver
 * throws rather than skipping it. That is deliberate: the project's original wound
 * was a world-name key mismatch that <em>silently dropped</em> every recorded
 * update (see PROJECT_STATUS.md, B3), so a determinism harness that quietly
 * skipped an unbound source would reintroduce exactly that class of invisible
 * gap and could report a false PASS. A missing binding is a setup error and is
 * surfaced loudly.
 */
public final class LiveLoadToggleDriver {

    private final DeterministicToggleSchedule schedule;
    private final Map<String, WorldPos> bindings;
    private final ToggleApplier applier;

    /**
     * @param schedule the pure toggle schedule; must be non-null
     * @param bindings sourceId → block position; must contain a binding for every
     *                 source id in {@code schedule.sourceIds()} and no null values
     * @param applier  the game-layer applier invoked per resolved toggle; may be
     *                 {@code null} if this driver is used only to resolve (e.g. in
     *                 tests or for dry-run planning), in which case {@link #driveTick}
     *                 will reject calls
     */
    public LiveLoadToggleDriver(DeterministicToggleSchedule schedule,
                                Map<String, WorldPos> bindings,
                                ToggleApplier applier) {
        if (schedule == null) {
            throw new IllegalArgumentException("schedule must not be null");
        }
        if (bindings == null) {
            throw new IllegalArgumentException("bindings must not be null");
        }
        Map<String, WorldPos> copy = Map.copyOf(bindings);
        for (String sourceId : schedule.sourceIds()) {
            if (!copy.containsKey(sourceId)) {
                throw new IllegalArgumentException(
                    "no position binding for schedule source '" + sourceId
                        + "' — every source must be bound (unbound sources would be "
                        + "silently dropped, reintroducing the B3 key-mismatch wound)");
            }
        }
        this.schedule = schedule;
        this.bindings = copy;
        this.applier = applier;
    }

    /**
     * Resolves the schedule's actions for {@code tick} into position-bearing
     * toggles, preserving the schedule's source-list order so the stream stays
     * stable and comparable across runs. Pure — does not touch the applier.
     *
     * @param tick game tick (must be ≥ 0)
     */
    public List<ResolvedToggle> resolveForTick(long tick) {
        List<ToggleAction> actions = schedule.actionsForTick(tick);
        List<ResolvedToggle> resolved = new ArrayList<>(actions.size());
        for (ToggleAction action : actions) {
            WorldPos pos = bindings.get(action.sourceId());
            // Cannot be null: the constructor verified every schedule source is
            // bound, and actionsForTick only emits schedule sources.
            resolved.add(new ResolvedToggle(pos, action.powered()));
        }
        return resolved;
    }

    /**
     * Resolves {@code tick}'s actions and applies each via the injected
     * {@link ToggleApplier}, in schedule order. Returns the toggles applied.
     *
     * @param tick game tick (must be ≥ 0)
     * @throws IllegalStateException if this driver was constructed without an applier
     */
    public List<ResolvedToggle> driveTick(long tick) {
        if (applier == null) {
            throw new IllegalStateException(
                "driveTick requires a ToggleApplier; this driver was constructed "
                    + "for resolve-only use");
        }
        List<ResolvedToggle> resolved = resolveForTick(tick);
        for (ResolvedToggle toggle : resolved) {
            applier.apply(tick, toggle);
        }
        return resolved;
    }

    /** The underlying deterministic schedule. */
    public DeterministicToggleSchedule schedule() {
        return schedule;
    }
}
