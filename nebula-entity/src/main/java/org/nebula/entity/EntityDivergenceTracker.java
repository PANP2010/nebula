package org.nebula.entity;

import org.nebula.core.math.Vec3;

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
 * <h3>Per-axis drift profile (which axis dominates? — 2026-07-09)</h3>
 * A scalar Euclidean drift says <em>how far</em> the model strayed but not <em>along
 * which axis</em> — and that is the question that decides whether write-back can be
 * armed. {@link org.nebula.entity.actions.EntityMoveAction} models only vertical
 * gravity/drag and resolves collision on the Y column alone, applying the horizontal
 * velocity components through unchanged. So the shape of the drift is diagnostic: if
 * the <b>Y</b> axis dominates, the gravity/drag constants (or the swept-collision
 * landing) are the systematic error to fix before arming write-back; if <b>X/Z</b>
 * dominate, the model simply isn't tracking Folia's horizontal movement (friction,
 * knockback, block collision) and a vertical-only mirror can never match. This tracker
 * therefore accumulates, alongside the scalar drift, the per-axis error
 * {@code predicted − authoritative}: both its <em>magnitude</em> (mean |Δx|,|Δy|,|Δz|,
 * to rank the axes) and its <em>signed</em> mean (to expose a systematic directional
 * bias — e.g. a persistently positive mean Δy means the model consistently predicts the
 * entity <em>higher</em> than Folia, i.e. it under-falls). {@link #dominantAxis()} names
 * the largest-magnitude axis and {@link #driftProfile()} renders the whole breakdown;
 * both are folded into {@link #summary()} so the live heartbeat answers "which axis
 * dominates?" directly, rather than leaving it to be inferred from a single number.
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

    // Per-axis drift accumulators (predicted − authoritative), so the live heartbeat can
    // answer "which axis dominates?" — see the per-axis-profile section of the class doc.
    // absSum* ranks the axes by magnitude; signedSum* exposes a systematic directional bias.
    private double absSumX;
    private double absSumY;
    private double absSumZ;
    private double signedSumX;
    private double signedSumY;
    private double signedSumZ;

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
                // Per-axis error = predicted − authoritative. |Δ| ranks the axes;
                // the signed value carries the directional bias (e.g. +Δy ⇒ model
                // predicts higher than Folia ⇒ under-falls).
                double dx = prev.predicted().x() - authoritative.x();
                double dy = prev.predicted().y() - authoritative.y();
                double dz = prev.predicted().z() - authoritative.z();
                absSumX += Math.abs(dx);
                absSumY += Math.abs(dy);
                absSumZ += Math.abs(dz);
                signedSumX += dx;
                signedSumY += dy;
                signedSumZ += dz;
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

    /** Mean magnitude of the per-frame X error (|predicted.x − authoritative.x|), 0 if none. */
    public double meanAbsDriftX() {
        return sampleCount == 0 ? 0.0 : absSumX / sampleCount;
    }

    /** Mean magnitude of the per-frame Y error, 0 if none. */
    public double meanAbsDriftY() {
        return sampleCount == 0 ? 0.0 : absSumY / sampleCount;
    }

    /** Mean magnitude of the per-frame Z error, 0 if none. */
    public double meanAbsDriftZ() {
        return sampleCount == 0 ? 0.0 : absSumZ / sampleCount;
    }

    /**
     * Mean <em>signed</em> X error (predicted − authoritative), 0 if none. A nonzero mean
     * is a systematic directional bias, not just noise; the sign says which way.
     */
    public double meanSignedDriftX() {
        return sampleCount == 0 ? 0.0 : signedSumX / sampleCount;
    }

    /** Mean signed Y error (predicted − authoritative), 0 if none. Positive ⇒ model predicts higher (under-falls). */
    public double meanSignedDriftY() {
        return sampleCount == 0 ? 0.0 : signedSumY / sampleCount;
    }

    /** Mean signed Z error (predicted − authoritative), 0 if none. */
    public double meanSignedDriftZ() {
        return sampleCount == 0 ? 0.0 : signedSumZ / sampleCount;
    }

    /**
     * A horizontal-axis error whose mean magnitude is below this floor (blocks/tick) is
     * treated as clean — not enough drift on that axis to characterize. Chosen well under
     * one tick of gravity (0.08) so it excludes numeric jitter, not real movement.
     */
    public static final double HORIZONTAL_DRIFT_FLOOR = 1e-4;

    /**
     * Fraction of an axis's error that is <em>directional</em> at or above which the drift
     * is deemed systematic rather than stochastic. 0.5 = the signed mean is at least half
     * the magnitude mean, i.e. the error leans one way more than it cancels.
     */
    public static final double SYSTEMATIC_BIAS_THRESHOLD = 0.5;

    /**
     * Bias-to-noise ratio for the X axis: {@code |meanSigned| / meanAbs}, in [0,1]. This is
     * the metric that answers option (a) of the C1 write-back pointer — <em>is the
     * horizontal drift a missing deterministic term or genuinely unpredictable AI motion?</em>
     * <ul>
     *   <li>≈1.0 — the per-frame error is one-directional (it barely cancels), the signature
     *       of a systematic term {@link org.nebula.entity.actions.EntityMoveAction} omits
     *       (e.g. horizontal friction/drag). <b>Modelable</b>: worth building a horizontal
     *       model before arming write-back.</li>
     *   <li>≈0.0 — the error is zero-mean scatter (signs cancel over samples), the signature
     *       of stochastic AI pathing/knockback. A deterministic vertical-or-horizontal mirror
     *       <b>can never</b> reproduce it; write-back must diff-only the horizontal.</li>
     * </ul>
     * Returns 0 when no sample has paired (no magnitude to divide).
     */
    public double biasRatioX() {
        return absSumX == 0.0 ? 0.0 : Math.abs(signedSumX) / absSumX;
    }

    /** Bias-to-noise ratio for the Y axis ({@code |meanSigned|/meanAbs}); see {@link #biasRatioX()}. */
    public double biasRatioY() {
        return absSumY == 0.0 ? 0.0 : Math.abs(signedSumY) / absSumY;
    }

    /** Bias-to-noise ratio for the Z axis ({@code |meanSigned|/meanAbs}); see {@link #biasRatioX()}. */
    public double biasRatioZ() {
        return absSumZ == 0.0 ? 0.0 : Math.abs(signedSumZ) / absSumZ;
    }

    /**
     * Classifies the horizontal (X/Z) drift into the verdict the write-back-arming decision
     * turns on. Only axes carrying more than {@link #HORIZONTAL_DRIFT_FLOOR} of mean-absolute
     * error are considered (a near-zero axis is neither systematic nor stochastic — it is
     * clean). Among the significant horizontal axes:
     * <ul>
     *   <li>{@code "horizontal-clean"} — neither X nor Z drifts meaningfully; a vertical
     *       model already tracks Folia horizontally.</li>
     *   <li>{@code "SYSTEMATIC"} — every significant axis is directional
     *       ({@link #biasRatioX()}/{@link #biasRatioZ()} ≥ {@link #SYSTEMATIC_BIAS_THRESHOLD}):
     *       a missing friction-like term, modelable.</li>
     *   <li>{@code "STOCHASTIC"} — every significant axis is zero-mean scatter (ratio below
     *       threshold): AI pathing a deterministic mirror can't reproduce.</li>
     *   <li>{@code "MIXED"} — one horizontal axis is systematic and the other stochastic.</li>
     *   <li>{@code "no-samples"} — nothing paired yet.</li>
     * </ul>
     */
    public String horizontalCharacter() {
        if (sampleCount == 0) return "no-samples";
        boolean xSignificant = meanAbsDriftX() > HORIZONTAL_DRIFT_FLOOR;
        boolean zSignificant = meanAbsDriftZ() > HORIZONTAL_DRIFT_FLOOR;
        if (!xSignificant && !zSignificant) return "horizontal-clean";
        boolean xSystematic = xSignificant && biasRatioX() >= SYSTEMATIC_BIAS_THRESHOLD;
        boolean zSystematic = zSignificant && biasRatioZ() >= SYSTEMATIC_BIAS_THRESHOLD;
        // A significant axis that isn't systematic is stochastic.
        boolean anyStochastic = (xSignificant && !xSystematic) || (zSignificant && !zSystematic);
        boolean anySystematic = xSystematic || zSystematic;
        if (anySystematic && anyStochastic) return "MIXED";
        return anySystematic ? "SYSTEMATIC" : "STOCHASTIC";
    }

    /**
     * One-line horizontal characterization: the {@link #horizontalCharacter()} verdict plus
     * the per-axis bias ratios that produced it. This is the direct answer to "is the X/Z
     * drift friction (modelable) or AI pathing (un-mirrorable)?".
     */
    public String horizontalProfile() {
        return String.format(
            "horizProfile: verdict=%s biasRatio[x=%.3f z=%.3f] (|signed|/|abs|; ~1=directional/modelable, ~0=stochastic/AI)",
            horizontalCharacter(), biasRatioX(), biasRatioZ());
    }

    /**
     * Names the axis carrying the largest mean absolute error — the axis that dominates
     * the drift, and thus the one a write-back-arming decision must scrutinize first.
     * Returns {@code "none"} when no sample has paired yet. Ties resolve X &gt; Y &gt; Z.
     */
    public String dominantAxis() {
        if (sampleCount == 0) return "none";
        double x = meanAbsDriftX();
        double y = meanAbsDriftY();
        double z = meanAbsDriftZ();
        if (x >= y && x >= z) return "X";
        if (y >= z) return "Y";
        return "Z";
    }

    /**
     * One-line per-axis breakdown: mean |Δ| per axis (magnitude ranking) plus the mean
     * signed Δ (directional bias) and the {@link #dominantAxis()}. This is the answer to
     * "which axis dominates the EntityMoveAction-vs-Folia drift?".
     */
    public String driftProfile() {
        return String.format(
            "axisProfile: dominant=%s meanAbs[x=%.6f y=%.6f z=%.6f] meanSigned[x=%+.6f y=%+.6f z=%+.6f]",
            dominantAxis(),
            meanAbsDriftX(), meanAbsDriftY(), meanAbsDriftZ(),
            meanSignedDriftX(), meanSignedDriftY(), meanSignedDriftZ());
    }

    /** Number of entities currently carrying a pending (unmatched) prediction. */
    public int trackedEntities() {
        return pending.size();
    }

    /** One-line human summary for a diagnostic log line. */
    public String summary() {
        return String.format(
            "entity-divergence: obs=%d samples=%d nonContiguousSkips=%d lastGap=%d "
                + "meanDrift=%.6f maxDrift=%.6f (entity=%d @tick=%d) tracked=%d | %s | %s",
            observationCount, sampleCount, nonContiguousSkips, lastGap,
            meanDrift(), maxDrift, maxDriftEntityId, maxDriftTick, pending.size(),
            driftProfile(), horizontalProfile());
    }
}
