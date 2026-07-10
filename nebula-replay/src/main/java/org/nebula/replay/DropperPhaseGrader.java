package org.nebula.replay;

import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure classifier for the dropper/dispenser eject {@code +1} offset (B8 C3 correctness):
 * decides whether the observe-only shadow's one-item lead over Folia's authoritative
 * self-inventory count is a harmless <em>ordering artifact</em> or a genuine
 * <em>rate divergence</em>. This is the eject twin of {@link FurnacePhaseGrader} — it shares
 * that grader's three-sample PHASE shape rather than {@link DropperSlotGapGrader}'s single
 * post-action GAP shape, for the reason below.
 *
 * <h3>Why a PHASE grader and not the GAP grader alone</h3>
 * {@link DropperSlotGapGrader} MEASURED the eject gap and found it a systematic, stable
 * {@code +1} (36ac531: Folia's self count exactly one ahead of the shadow's, both stepping
 * down in lockstep as Folia's hopper refilled). But a post-action-only sample cannot tell a
 * harmless one-step ORDERING lead (Folia's authoritative eject/refill lands one tick before
 * the observe-only shadow's {@code syncFromNms} samples it, yet the shadow steps at Folia's
 * rate) apart from a genuine RATE divergence (a DOUBLE-EJECTOR: both Folia and the DAG remove
 * a source item every pulse, so the shadow ejects faster than Folia). Under the first, the
 * gap is a benign offset and the safe-mirror precondition is really met; under the second the
 * gap would grow, and arming a dropper eject write-back is the double-writer trap. To separate
 * them the phase probe captures, on one region-thread pass, three self-inventory counts:
 * <ul>
 *   <li>{@code folia} — Folia's authoritative summed self count (region-thread read).</li>
 *   <li>{@code pre} — the CAS self count <em>after</em> {@code syncFromNms} rebased it to
 *       Folia but <em>before</em> the DAG eject action ran.</li>
 *   <li>{@code post} — the CAS self count <em>after</em> the DAG eject action ran.</li>
 * </ul>
 * <b>ORDERING artifact (the benign case):</b> {@code pre == folia} exactly — the sync landed
 * CAS onto Folia — and the only offset is {@code post - pre}, the action's own single eject
 * step ({@code -1} for a pulsing dropper, {@code 0} for a resting one). The shadow steps at
 * Folia's rate; the BE-DROPPER-SLOT {@code +1} was purely that the slot grader sampled
 * {@code post} against a same-tick Folia read. Under this verdict a guarded write-back sampled
 * PRE-action could be honest — exactly the classification {@code eea10db} reached for the
 * furnace-timer {@code +1}.
 *
 * <p><b>RATE divergence (the trap):</b> {@code pre != folia} — CAS strayed from Folia BEFORE
 * the action, a gap {@code syncFromNms} should have erased, so the model's eject cadence
 * advances at a different rate than vanilla. Under this verdict the shadow is NOT a safe
 * mirror and the dropper must be left to Folia; arming write-back would over-eject the source.
 *
 * <h3>Expected benign step</h3>
 * For a pulsing dropper the action's honest one-tick step is {@code -1} (one source item
 * ejected; see {@link org.nebula.entity.actions.BlockEntityActions#dropper}). A dropper at
 * rest (all slots empty) steps by {@code 0} — such samples carry {@code pre == post} and are
 * simply not evidence either way. This classifier does NOT hard-code the step as a correctness
 * oracle — it grades the PRE gap (which must be 0 for an ordering artifact) and separately
 * reports the observed post-step so a reader can confirm it matches the expected eject.
 *
 * <p>This class is pure: it parses {@code BE-DROPPER-PHASE:} lines and classifies the
 * pre-action gap against an item tolerance. It performs no I/O. Marker
 * {@code BE-DROPPER-PHASE:} is disjoint from {@code BE-DROPPER-SLOT:},
 * {@code BE-FURNACE-PHASE:}, {@code BE-FURNACE-TIMER:}, {@code BE-SETTLED:} and
 * {@code SETTLED-DIAG:} so one {@code server-run.log} grades cleanly on every path.
 */
public final class DropperPhaseGrader {

    /** Marker every dropper-phase snapshot line carries. */
    static final String PHASE_MARKER = "BE-DROPPER-PHASE:";

    // One tracked dropper/dispenser rendered by the plugin, e.g.
    //   DROPPER WorldPos[dimensionId=0, x=100, y=64, z=100] foliaSelf=8 preSelf=8 postSelf=7
    // The leading \w+ captures the block-entity type (DROPPER or DISPENSER); matching each
    // position directly is robust to the commas inside the WorldPos record rendering,
    // exactly as DropperSlotGapGrader.POSITION does.
    private static final Pattern POSITION = Pattern.compile(
        "(\\w+) WorldPos\\[dimensionId=(-?\\d+), x=(-?\\d+), y=(-?\\d+), z=(-?\\d+)\\]"
            + " foliaSelf=(-?\\d+) preSelf=(-?\\d+) postSelf=(-?\\d+)");
    private static final Pattern TICK = Pattern.compile("tick=(-?\\d+)");
    private static final Pattern TRACKED = Pattern.compile("tracked=(-?\\d+)");

    private DropperPhaseGrader() {
    }

    /**
     * One dropper/dispenser's phase sample: Folia's authoritative summed self count, the CAS
     * self count just after {@code syncFromNms} (pre-action) and just after the DAG eject
     * action (post-action). The decisive quantity is {@link #preGap()} — how far CAS strayed
     * from Folia BEFORE the action. Zero means the sync rebased exactly and the only offset is
     * the action's own eject step.
     */
    public record DropperPhaseSample(WorldPos pos, String type,
                                     int foliaSelf, int preSelf, int postSelf) {
        /** Absolute pre-action self-count gap between CAS and Folia — the decisive quantity. */
        public int preGap() {
            return Math.abs(preSelf - foliaSelf);
        }

        /** The action's observed self-count step this tick ({@code post - pre}). */
        public int selfStep() {
            return postSelf - preSelf;
        }

        /**
         * {@code true} iff CAS strayed from Folia before the action ran by more than
         * {@code toleranceItems} — i.e. this sample is evidence of a genuine RATE divergence,
         * not a benign ordering lead.
         */
        public boolean rateDiverged(int toleranceItems) {
            return preGap() > toleranceItems;
        }
    }

    /**
     * One snapshot as parsed from a {@code BE-DROPPER-PHASE:} line. {@code tracked} is the
     * plugin-reported count; the grader uses the parsed positions as ground truth.
     */
    public record DropperPhaseSnapshot(int tick, int tracked, List<DropperPhaseSample> positions) {
        public DropperPhaseSnapshot {
            positions = List.copyOf(positions);
        }
    }

    /**
     * Parses every {@code BE-DROPPER-PHASE:} line from a server log into snapshots,
     * preserving log order. Lines without the marker are ignored, so a raw
     * {@code server-run.log} can be fed in directly.
     */
    public static List<DropperPhaseSnapshot> parse(List<String> logLines) {
        if (logLines == null) {
            throw new IllegalArgumentException("logLines must not be null");
        }
        List<DropperPhaseSnapshot> out = new ArrayList<>();
        for (String line : logLines) {
            if (line == null || !line.contains(PHASE_MARKER)) {
                continue;
            }
            List<DropperPhaseSample> positions = new ArrayList<>();
            Matcher m = POSITION.matcher(line);
            while (m.find()) {
                WorldPos pos = new WorldPos(
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)),
                    Integer.parseInt(m.group(5)));
                positions.add(new DropperPhaseSample(pos, m.group(1),
                    Integer.parseInt(m.group(6)),
                    Integer.parseInt(m.group(7)),
                    Integer.parseInt(m.group(8))));
            }
            out.add(new DropperPhaseSnapshot(
                intField(TICK, line),
                intField(TRACKED, line),
                positions));
        }
        return out;
    }

    /** Splits a multi-line log blob on newlines and delegates to {@link #parse(List)}. */
    public static List<DropperPhaseSnapshot> parse(String log) {
        if (log == null) {
            throw new IllegalArgumentException("log must not be null");
        }
        return parse(List.of(log.split("\\R", -1)));
    }

    /**
     * Classifies the dropper eject offset across every snapshot's positions by grading the
     * PRE-action CAS-vs-Folia gap.
     *
     * @param snapshots      parsed snapshots, in log order
     * @param toleranceItems the largest pre-action gap that still counts as "sync rebased
     *                       exactly" (an ordering artifact). {@code 0} demands CAS == Folia
     *                       before the action. Must be {@code >= 0}.
     * @return a report whose verdict is INCONCLUSIVE when no positions were sampled, PASS when
     *         every dropper's pre-action gap is within tolerance (the offset is a pure ordering
     *         artifact — a write-back sampled pre-action could be honest), else FAIL (a genuine
     *         rate divergence — leave the dropper to Folia)
     */
    public static DropperPhaseReport grade(List<DropperPhaseSnapshot> snapshots, int toleranceItems) {
        if (snapshots == null) {
            throw new IllegalArgumentException("snapshots must not be null");
        }
        if (toleranceItems < 0) {
            throw new IllegalArgumentException("toleranceItems must be >= 0");
        }
        int samples = 0;
        int rateDiverged = 0;
        int maxPreGap = 0;
        long sumPreGap = 0;
        int minSelfStep = Integer.MAX_VALUE;
        int maxSelfStep = Integer.MIN_VALUE;
        for (DropperPhaseSnapshot snap : snapshots) {
            for (DropperPhaseSample p : snap.positions()) {
                samples++;
                maxPreGap = Math.max(maxPreGap, p.preGap());
                sumPreGap += p.preGap();
                minSelfStep = Math.min(minSelfStep, p.selfStep());
                maxSelfStep = Math.max(maxSelfStep, p.selfStep());
                if (p.rateDiverged(toleranceItems)) {
                    rateDiverged++;
                }
            }
        }
        if (samples == 0) {
            minSelfStep = maxSelfStep = 0;
        }
        return new DropperPhaseReport(snapshots.size(), samples, rateDiverged,
            maxPreGap, sumPreGap, minSelfStep, maxSelfStep, toleranceItems);
    }

    private static int intField(Pattern p, String line) {
        Matcher m = p.matcher(line);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return -1;
    }

    /**
     * Classification result. The verdict grades the PRE-action gap (ordering vs rate), and the
     * observed self-count step range is surfaced so a reader can confirm the action stepped by
     * the expected amount ({@code -1} for a pulsing dropper, {@code 0} at rest) rather than
     * inferring it.
     */
    public record DropperPhaseReport(int snapshots, int sampledPositions, int rateDivergedPositions,
                                     int maxPreGap, long sumPreGap,
                                     int minSelfStep, int maxSelfStep,
                                     int toleranceItems) {
        /** The largest pre-action gap observed — the decisive quantity. */
        public int maxObservedPreGap() {
            return maxPreGap;
        }

        /** Mean pre-action self-count gap over sampled positions; 0.0 when none. */
        public double meanPreGap() {
            return sampledPositions == 0 ? 0.0 : (double) sumPreGap / sampledPositions;
        }

        /** Fraction of droppers whose pre-action gap exceeds tolerance; 0.0 when none. */
        public double rateDivergenceRate() {
            return sampledPositions == 0 ? 0.0 : (double) rateDivergedPositions / sampledPositions;
        }

        public FoliaDivergenceGrader.Verdict verdict() {
            if (sampledPositions == 0) {
                return FoliaDivergenceGrader.Verdict.INCONCLUSIVE;
            }
            return maxObservedPreGap() <= toleranceItems
                ? FoliaDivergenceGrader.Verdict.PASS
                : FoliaDivergenceGrader.Verdict.FAIL;
        }

        public boolean passed() {
            return verdict() == FoliaDivergenceGrader.Verdict.PASS;
        }

        /**
         * A one-word plain-English label for the verdict: {@code ORDERING-ARTIFACT} (PASS),
         * {@code RATE-DIVERGENCE} (FAIL) or {@code NO-SAMPLES} (INCONCLUSIVE).
         */
        public String classification() {
            return switch (verdict()) {
                case PASS -> "ORDERING-ARTIFACT";
                case FAIL -> "RATE-DIVERGENCE";
                case INCONCLUSIVE -> "NO-SAMPLES";
            };
        }
    }
}
