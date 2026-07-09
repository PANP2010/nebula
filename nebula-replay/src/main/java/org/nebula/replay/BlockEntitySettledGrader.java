package org.nebula.replay;

import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure grader for the <em>settled-state</em> Folia-vs-Nebula divergence signal on the
 * BLOCK-ENTITY path (B8 C3 correctness; the block-entity twin of
 * {@link SettledDivergenceGrader}, which grades the redstone path).
 *
 * <h3>Why the block-entity path needs a settled grader at all</h3>
 * The block-entity {@code BE-CAS-DIAG} stream (emitted per shadow tick when
 * {@code /nebula diag on}) proved LIVE that Nebula is observe-only on hoppers: Folia
 * arms a hopper's 8-tick transfer cooldown before the shadow's {@code syncFromNms}
 * ever samples it, so the shadow reads {@code cooldown=7} on every tick and never
 * catches an in-shadow-tick CAS item move (see commit 3858abb — 91/91 lines
 * {@code cooldown=7}, zero {@code [MOVED]}). That means the per-tick delta stream is
 * the wrong surface for a correctness check, exactly as the redstone CASCADE-DIAG
 * residual-dirty-rate is (see {@link FoliaDivergenceGrader}'s javadoc): a
 * never-settling hopper is always sampled mid-cooldown, where a count mismatch is
 * expected observe-lag, not a bug.
 *
 * <p>But that same live trace showed the hopper's self-slot count <em>converges to a
 * stable value at quiescence</em> (it climbed 15→…→113 as Folia pulled items in, then
 * declined to a resting count once the feed stopped). Quiescence is therefore
 * well-defined for the block-entity path too, and it is the honest correctness
 * surface: once a hopper has stopped transferring, the observe-only shadow has had
 * every intervening tick to catch up, so <strong>any {@code nebula != folia} on the
 * settled inventory count is a genuine Folia-vs-Nebula divergence, not cooldown
 * lag.</strong>
 *
 * <h3>The signal this grader consumes</h3>
 * Once the tracked block entities are at rest, the plugin snapshots each one and logs
 * a {@code BE-SETTLED:} line comparing Nebula's CAS inventory count ({@code nebula=X})
 * against Folia's authoritative inventory count ({@code folia=Y}) per position, tagged
 * with the block-entity type. {@code nebula}/{@code folia} are the summed item counts
 * across the block entity's inventory slots — the same {@code self-slots} quantity the
 * {@code BE-CAS-DIAG} trace already tracks, and the quantity the int-count model
 * represents. At true quiescence a correct shadow matches Folia exactly.
 *
 * <p>This class is pure: it parses {@code BE-SETTLED:} lines and grades the divergence
 * rate against a threshold. The default threshold is {@code 0.0} — at genuine
 * quiescence a correct shadow matches Folia exactly. It performs no I/O and knows
 * nothing about how the snapshot is produced; the plugin emit + CLI live run are a
 * separate slice, mirroring how {@link SettledDivergenceGrader} shipped as a pure core
 * before its live harness.
 *
 * <h3>Marker isolation from the redstone grader</h3>
 * The marker is {@code BE-SETTLED:}, deliberately NOT a substring of the redstone
 * grader's {@code SETTLED-DIAG:} (and vice versa). Both graders filter by their own
 * marker before parsing, so a single {@code server-run.log} containing BOTH a redstone
 * {@code SETTLED-DIAG} line and a block-entity {@code BE-SETTLED} line grades cleanly
 * on each path with no cross-contamination.
 */
public final class BlockEntitySettledGrader {

    /** Marker every block-entity settled-snapshot line carries. */
    static final String SETTLED_MARKER = "BE-SETTLED:";

    // One tracked block entity rendered by the plugin, e.g.
    //   HOPPER WorldPos[dimensionId=0, x=0, y=64, z=0] nebula=48 folia=48
    // The leading \w+ captures the block-entity type; matching each position sample
    // directly (rather than splitting on commas) is robust to the commas inside the
    // WorldPos record rendering, exactly as SettledDivergenceGrader.POSITION does.
    private static final Pattern POSITION = Pattern.compile(
        "(\\w+) WorldPos\\[dimensionId=(-?\\d+), x=(-?\\d+), y=(-?\\d+), z=(-?\\d+)\\]"
            + " nebula=(-?\\d+) folia=(-?\\d+)");
    private static final Pattern TICK = Pattern.compile("tick=(-?\\d+)");
    private static final Pattern TRACKED = Pattern.compile("tracked=(-?\\d+)");

    private BlockEntitySettledGrader() {
    }

    /**
     * One tracked block entity's settled-state sample. {@link #diverged()} is the
     * correctness bit: at quiescence {@code nebula != folia} is a real divergence.
     * {@code nebula}/{@code folia} are the summed inventory item counts.
     */
    public record BlockEntitySample(WorldPos pos, String type, int nebula, int folia) {
        public boolean diverged() {
            return nebula != folia;
        }
    }

    /**
     * One quiescence snapshot as parsed from a {@code BE-SETTLED:} line. The
     * {@code tracked} field is the plugin-reported count; the grader uses the parsed
     * positions as ground truth and surfaces {@code tracked} for cross-checking.
     */
    public record BlockEntitySettledSnapshot(int tick, int tracked, List<BlockEntitySample> positions) {
        public BlockEntitySettledSnapshot {
            positions = List.copyOf(positions);
        }
    }

    /**
     * Parses every {@code BE-SETTLED:} line from a server log into snapshots,
     * preserving log order. Lines without the marker are ignored, so a raw
     * {@code server-run.log} can be fed in directly.
     */
    public static List<BlockEntitySettledSnapshot> parse(List<String> logLines) {
        if (logLines == null) {
            throw new IllegalArgumentException("logLines must not be null");
        }
        List<BlockEntitySettledSnapshot> out = new ArrayList<>();
        for (String line : logLines) {
            if (line == null || !line.contains(SETTLED_MARKER)) {
                continue;
            }
            List<BlockEntitySample> positions = new ArrayList<>();
            Matcher m = POSITION.matcher(line);
            while (m.find()) {
                WorldPos pos = new WorldPos(
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)),
                    Integer.parseInt(m.group(5)));
                positions.add(new BlockEntitySample(pos, m.group(1),
                    Integer.parseInt(m.group(6)),
                    Integer.parseInt(m.group(7))));
            }
            out.add(new BlockEntitySettledSnapshot(
                intField(TICK, line),
                intField(TRACKED, line),
                positions));
        }
        return out;
    }

    /** Splits a multi-line log blob on newlines and delegates to {@link #parse(List)}. */
    public static List<BlockEntitySettledSnapshot> parse(String log) {
        if (log == null) {
            throw new IllegalArgumentException("log must not be null");
        }
        return parse(List.of(log.split("\\R", -1)));
    }

    /**
     * Grades the settled-state divergence rate across every snapshot's positions.
     *
     * @param snapshots         parsed settled snapshots, in log order
     * @param maxDivergenceRate the pass threshold on the divergence rate, in [0,1];
     *                          {@code 0.0} means "exact agreement required at
     *                          quiescence" (the honest default — see class javadoc)
     * @return a report whose verdict is INCONCLUSIVE when no positions were sampled,
     *         else PASS/FAIL against the threshold
     */
    public static BlockEntitySettledReport grade(List<BlockEntitySettledSnapshot> snapshots,
                                                  double maxDivergenceRate) {
        if (snapshots == null) {
            throw new IllegalArgumentException("snapshots must not be null");
        }
        if (maxDivergenceRate < 0.0 || maxDivergenceRate > 1.0) {
            throw new IllegalArgumentException("maxDivergenceRate must be in [0,1]");
        }
        int samples = 0;
        int diverged = 0;
        for (BlockEntitySettledSnapshot snap : snapshots) {
            for (BlockEntitySample p : snap.positions()) {
                samples++;
                if (p.diverged()) {
                    diverged++;
                }
            }
        }
        return new BlockEntitySettledReport(snapshots.size(), samples, diverged, maxDivergenceRate);
    }

    private static int intField(Pattern p, String line) {
        Matcher m = p.matcher(line);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return -1;
    }

    /**
     * Grading result for the block-entity settled-state check. Like
     * {@link SettledDivergenceGrader.SettledReport} there is no converge window: every
     * graded sample is taken <em>at</em> quiescence, so there is no cold-start
     * transient to exclude — a diverged sample is a real divergence.
     */
    public record BlockEntitySettledReport(int snapshots, int sampledPositions, int divergedPositions,
                                           double maxDivergenceRate) {
        /** Divergence rate over sampled positions; 0.0 when there are none. */
        public double divergenceRate() {
            return sampledPositions == 0 ? 0.0 : (double) divergedPositions / sampledPositions;
        }

        public FoliaDivergenceGrader.Verdict verdict() {
            if (sampledPositions == 0) {
                return FoliaDivergenceGrader.Verdict.INCONCLUSIVE;
            }
            return divergenceRate() <= maxDivergenceRate
                ? FoliaDivergenceGrader.Verdict.PASS
                : FoliaDivergenceGrader.Verdict.FAIL;
        }

        public boolean passed() {
            return verdict() == FoliaDivergenceGrader.Verdict.PASS;
        }
    }
}
