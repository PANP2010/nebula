package org.nebula.entity;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Observe-only frame-for-frame divergence signal for the entity DAG (B8 C1,
 * arch doc §6.2) — the entity analogue of {@code FoliaDivergenceGrader}.
 *
 * <h3>What the signal is</h3>
 * On each entity tick the live pipeline (a) reads Folia's <em>authoritative</em>
 * post-move position via {@code syncPhysicsFromNms}, then (b) runs
 * {@link org.nebula.entity.actions.EntityMoveAction}, which computes an
 * approximate <em>predicted</em> next position from its own gravity/drag/collision
 * model. This tracker records, per entity, the prediction made on tick {@code t}
 * and — on the next <em>contiguous</em> tick {@code t+1} — diffs it against the
 * authoritative position Folia actually produced. The Euclidean distance between
 * the two is that tick's drift: how far Nebula's shadow physics strayed from
 * vanilla over one frame.
 *
 * <h3>Why contiguity matters (the honesty guard)</h3>
 * A sample is emitted <b>only</b> when this observation's tick is exactly one
 * past the previous observation's tick for the same entity. The seed source is
 * {@code EntityMoveEvent}, which fires only when an entity actually moves, so an
 * entity that stands still (or leaves the region and returns) produces a tick
 * gap. Comparing a prediction across such a gap would diff a one-tick forecast
 * against a position many ticks later — a meaningless "divergence" that is really
 * just an unobserved interval. Skipping non-contiguous pairs keeps every sample a
 * true frame-for-frame comparison, mirroring the redstone grader's boundary-lag
 * exclusion. First sighting of an entity likewise emits no sample (no prior
 * prediction to compare).
 *
 * <h3>What a nonzero drift does and does NOT prove</h3>
 * This measures the gap between {@link org.nebula.entity.actions.EntityMoveAction}'s
 * approximate constants (gravity=-0.08, drag=0.98, vertical-only swept collision)
 * and Folia's authoritative movement. A nonzero drift is <em>expected</em> today
 * precisely because the write-back is gated OFF: the model is known to be
 * approximate. The tracker's job is to <b>quantify</b> that gap so a future cycle
 * can decide whether the model is close enough to arm write-back (option b of the
 * C1 pointer) or whether write-back must instead diff-only (option a). It is a
 * measurement, not a pass/fail correctness proof — do not read "low drift" as
 * "entity physics matches vanilla."
 *
 * <h3>Observability of a silent run (live cadence finding, 2026-07-09)</h3>
 * The drain cadence is what makes frame-for-frame pairing possible or not, and this is
 * a live-verified subtlety worth stating. {@code EntityMoveAction} forecasts exactly
 * <b>one game tick</b> of gravity/drag ahead, so the tracker samples only when
 * consecutive observations for an entity are one game tick apart ({@link #lastGap()}==1).
 * An earlier driver alternated begin/end (running the entity DAG every OTHER game tick),
 * so {@code Server.getCurrentTick()} jumped by <b>2</b> between passes and every pair was
 * non-contiguous — zero samples, correctly. That was NOT to be fixed by loosening the
 * guard (a 1-tick forecast diffed against a 2-tick-later reality is a spurious
 * "divergence"); it was fixed by draining the entity hook <em>every</em> game tick so one
 * DAG pass == one game tick and the {@code gap==1} guard yields honest samples.
 * Regardless, {@link #observationCount()}, {@link #nonContiguousSkips()} and
 * {@link #lastGap()} are exposed and folded into {@link #summary()}: a heartbeat of
 * {@code obs=N samples=M nonContiguousSkips=K lastGap=G} positively evidences the signal
 * is live and, if samples stay 0, pinpoints the cadence as the reason — a tracker that
 * only counted samples would be indistinguishable from one that never ran (the exact
 * documentation-drift trap this project exists to avoid).
 *
 * <p>Pure and single-threaded: one instance is driven from a region thread's
 * {@code executeOwnedEntityDag}; it holds no NMS or Folia references and is fully
 * unit-provable without a live server.
 */
public final class EntityDivergenceTracker {

    /** One frame-for-frame drift observation. */
    public record Sample(long entityId, long tick, Vec3 predicted, Vec3 authoritative, double drift) {}

    private record Pending(long tick, Vec3 predicted) {}

    private final Map<Long, Pending> pending = new HashMap<>();

    private long observationCount;
    private long sampleCount;
    private long nonContiguousSkips;
    private long lastGap;
    private double driftSum;
    private double maxDrift;
    private long maxDriftEntityId;
    private long maxDriftTick;

    /**
     * Records tick {@code tick} for {@code entityId}: {@code authoritative} is the
     * position Folia produced for this tick, {@code predicted} is the position
     * {@link org.nebula.entity.actions.EntityMoveAction} computed this tick for the
     * <em>next</em> tick. Returns a drift {@link Sample} iff a prediction from the
     * immediately preceding tick exists for this entity (contiguous frames);
     * otherwise stores the prediction and returns empty.
     */
    public Optional<Sample> record(long entityId, long tick, Vec3 authoritative, Vec3 predicted) {
        observationCount++;
        Pending prev = pending.get(entityId);
        Optional<Sample> result = Optional.empty();
        if (prev != null) {
            lastGap = tick - prev.tick();
            if (prev.tick() == tick - 1) {
                double drift = prev.predicted().distanceTo(authoritative);
                Sample sample = new Sample(entityId, tick, prev.predicted(), authoritative, drift);
                sampleCount++;
                driftSum += drift;
                if (drift > maxDrift) {
                    maxDrift = drift;
                    maxDriftEntityId = entityId;
                    maxDriftTick = tick;
                }
                result = Optional.of(sample);
            } else {
                // A prior prediction existed but this observation is not the immediately
                // following tick — a non-contiguous pair. Counted (not silently dropped)
                // so a caller can distinguish "tracker never ran" from "tracker ran but
                // no frame-for-frame pair was comparable". See class doc on contiguity.
                nonContiguousSkips++;
            }
        }
        pending.put(entityId, new Pending(tick, predicted));
        return result;
    }

    /** Total observations fed in, whether or not they yielded a contiguous sample. */
    public long observationCount() {
        return observationCount;
    }

    /**
     * Observations that had a prior prediction but were not the immediately following
     * tick (tick gap != 1). A high count with {@link #sampleCount()} == 0 means the
     * drain cadence isn't frame-for-frame — the signal to fix, not to loosen the guard.
     */
    public long nonContiguousSkips() {
        return nonContiguousSkips;
    }

    /** Tick gap of the most recent paired observation (0 if none seen yet). */
    public long lastGap() {
        return lastGap;
    }

    /** Number of contiguous frame-for-frame samples graded so far. */
    public long sampleCount() {
        return sampleCount;
    }

    /** Mean drift across all samples (0 if none). */
    public double meanDrift() {
        return sampleCount == 0 ? 0.0 : driftSum / sampleCount;
    }

    /** Largest single-frame drift observed (0 if none). */
    public double maxDrift() {
        return maxDrift;
    }

    public long maxDriftEntityId() {
        return maxDriftEntityId;
    }

    public long maxDriftTick() {
        return maxDriftTick;
    }

    /** Number of entities currently carrying a pending (unmatched) prediction. */
    public int trackedEntities() {
        return pending.size();
    }

    /** One-line human summary for a diagnostic log line. */
    public String summary() {
        return String.format(
            "entity-divergence: obs=%d samples=%d nonContiguousSkips=%d lastGap=%d "
                + "meanDrift=%.6f maxDrift=%.6f (entity=%d @tick=%d) tracked=%d",
            observationCount, sampleCount, nonContiguousSkips, lastGap,
            meanDrift(), maxDrift, maxDriftEntityId, maxDriftTick, pending.size());
    }
}
