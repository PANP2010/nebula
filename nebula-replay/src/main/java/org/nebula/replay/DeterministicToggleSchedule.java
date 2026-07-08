package org.nebula.replay;

import java.util.ArrayList;
import java.util.List;

/**
 * A pure, seed-reproducible schedule of redstone-source toggles for driving a
 * live-load zero-diff capture (arch doc §12.1; DG1 Criterion 1 live-load slice).
 *
 * <h3>Why this exists</h3>
 * The static-world zero-diff harness ({@code scripts/zerodiff-harness.sh}) proves
 * the capture+hash pipeline is deterministic, but it cannot exercise the DAG under
 * sustained live redstone activity: RCON toggles are not tick-aligned between two
 * independent runs (a {@code setblock} lands on whatever tick the command thread
 * reaches), so live toggling would make two runs legitimately differ and defeat the
 * byte-identity comparison.
 *
 * <p>This class removes that obstacle for the <em>scheduling</em> half of the
 * problem: it is a deterministic function {@code tick -> List<ToggleAction>} derived
 * entirely from a seed and the source list. Given the same seed and sources, two
 * independent runs produce the <strong>identical</strong> toggle stream tick-for-tick,
 * so any state divergence between runs is attributable to the engine, not to input
 * timing. The remaining half — applying these actions on the correct region thread at
 * the correct tick — is the game-layer's job and is deliberately out of scope here so
 * this logic stays free of Folia/NMS types and fully unit-testable.
 *
 * <h3>Toggle model</h3>
 * Each source emits a deterministic square wave: it flips on ticks
 * {@code phase, phase+period, phase+2*period, ...}, where {@code phase} is a
 * per-source offset in {@code [0, period)} derived from the seed. The first flip of
 * every source drives it {@code powered=true}; flips then alternate. Per-source phase
 * offsets spread the activity across ticks rather than firing every source at once,
 * producing a sustained, multi-region-friendly workload.
 *
 * <p>Determinism guarantees:
 * <ul>
 *   <li>{@link #actionsForTick(long)} depends only on the constructor arguments and
 *       the tick number — never on wall-clock, iteration order, or hashing of
 *       mutable state.</li>
 *   <li>Actions for a tick are returned in the source-list order supplied to the
 *       constructor, so the stream is stable and comparable.</li>
 * </ul>
 */
public final class DeterministicToggleSchedule {

    private final List<String> sourceIds;
    private final long seed;
    private final int period;
    private final int[] phases;

    /**
     * @param sourceIds opaque stable source identifiers, in a fixed order; must be
     *                  non-empty and contain no nulls
     * @param seed      determinism seed — same seed + sources ⇒ same toggle stream
     * @param period    ticks between successive flips of a single source; must be ≥ 1
     */
    public DeterministicToggleSchedule(List<String> sourceIds, long seed, int period) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            throw new IllegalArgumentException("sourceIds must be non-empty");
        }
        if (period < 1) {
            throw new IllegalArgumentException("period must be >= 1, was " + period);
        }
        this.sourceIds = List.copyOf(sourceIds);
        this.seed = seed;
        this.period = period;
        this.phases = new int[this.sourceIds.size()];
        for (int i = 0; i < this.sourceIds.size(); i++) {
            if (this.sourceIds.get(i) == null) {
                throw new IllegalArgumentException("sourceIds must not contain null (index " + i + ")");
            }
            // Derive a stable per-source phase in [0, period) from the seed and index.
            long h = mix(seed ^ mix(i));
            this.phases[i] = (int) (Long.remainderUnsigned(h, period));
        }
    }

    /**
     * Returns the toggle actions that fire on the given tick, in source-list order.
     * Empty if no source flips on this tick.
     *
     * @param tick game tick (must be ≥ 0)
     */
    public List<ToggleAction> actionsForTick(long tick) {
        if (tick < 0) {
            throw new IllegalArgumentException("tick must be >= 0, was " + tick);
        }
        List<ToggleAction> actions = new ArrayList<>();
        for (int i = 0; i < sourceIds.size(); i++) {
            long delta = tick - phases[i];
            if (delta >= 0 && delta % period == 0) {
                long flipIndex = delta / period;
                boolean powered = (flipIndex % 2) == 0;
                actions.add(new ToggleAction(sourceIds.get(i), powered));
            }
        }
        return actions;
    }

    /** The number of source flips that occur across ticks {@code [0, tickCount)}. */
    public long totalActions(long tickCount) {
        if (tickCount < 0) {
            throw new IllegalArgumentException("tickCount must be >= 0, was " + tickCount);
        }
        long total = 0;
        for (int phase : phases) {
            if (tickCount > phase) {
                // Flips at phase, phase+period, ... strictly below tickCount.
                total += (tickCount - 1 - phase) / period + 1;
            }
        }
        return total;
    }

    /** Source identifiers in schedule order. */
    public List<String> sourceIds() {
        return sourceIds;
    }

    /** The determinism seed. */
    public long seed() {
        return seed;
    }

    /** The per-source flip period in ticks. */
    public int period() {
        return period;
    }

    /**
     * SplitMix64 finalising mix — a pure, well-distributed 64-bit hash with no
     * dependence on platform, JVM, or iteration order, so phase derivation is
     * bit-for-bit reproducible across runs and machines.
     */
    private static long mix(long z) {
        z = z + 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
