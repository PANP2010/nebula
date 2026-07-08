package org.nebula.replay;

import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure grader for the Folia-vs-Nebula divergence signal already emitted by
 * {@code /nebula diag on} (DG3 correctness slice; arch doc §12.3).
 *
 * <h3>What the signal is</h3>
 * When the cascade diagnostic is enabled, {@code NebulaPlugin.executeOwnedDag}
 * logs one {@code CASCADE-DIAG} line per invocation. Each seed carries a
 * {@code cas=X→nms=Y (settled|dirty)} sample where:
 * <ul>
 *   <li>{@code casBefore} (= {@code X}) is the value Nebula's DAG shadow last
 *       wrote for that position (its CAS store value <em>entering</em> the
 *       invocation), and</li>
 *   <li>{@code nmsAfter} (= {@code Y}) is Folia's <em>authoritative</em> power
 *       level, freshly pulled by {@code syncFromNms} at the top of the same
 *       invocation.</li>
 * </ul>
 * So {@code (dirty)} (X≠Y) is a point where Nebula's shadow trajectory disagreed
 * with Folia's authoritative redstone, and {@code (settled)} (X==Y) is agreement.
 * This is exactly the Folia-vs-Nebula divergence check that driven zero-diff
 * (a Nebula-vs-Nebula seed-consistency check) does <em>not</em> provide, and it
 * reuses the existing log format rather than a new capture surface.
 *
 * <h3>Honest interpretation — what a nonzero dirty rate does and does NOT prove</h3>
 * Nebula runs <strong>observe-only</strong> on the redstone path (PROJECT_STATUS.md
 * B4): the shadow reads {@code casBefore} (last written), then {@code syncFromNms}
 * pulls Folia's current value. Whenever Folia has <em>just</em> changed a position,
 * {@code casBefore} is stale by one observe cycle, so that seed reads {@code dirty}
 * even though the shadow is behaving correctly — it simply catches up on the same
 * invocation (via {@code syncToNms}). Therefore a dirty sample taken <em>at a
 * toggle boundary</em> is expected observe-only lag, not a bug, and under a driven
 * square wave a steady stream of boundary-dirty samples is normal. The load-bearing
 * correctness signal is <strong>sustained</strong> divergence that does not settle
 * after the cold-CAS opening transient. This grader therefore skips a leading
 * {@code convergeWindow} of invocations (the cold-start transient, mirroring the
 * zerodiff-harness CONVERGE_MAX prefix) and grades the residual dirty rate against
 * a threshold. A low residual rate says the shadow tracks Folia once warm; it does
 * NOT by itself distinguish expected boundary lag from a real bug — that separation
 * needs settled-state sampling (a future slice), which is why the default threshold
 * is a loose starting bound, not a physics constant.
 */
public final class FoliaDivergenceGrader {

    /** Marker every diagnostic line carries. */
    static final String DIAG_MARKER = "CASCADE-DIAG:";

    // WorldPos renders via the record default, e.g. "WorldPos[dimensionId=0, x=1, y=-60, z=2]".
    // The '→' is the '→' arrow the plugin writes between cas and nms. Matching each
    // seed sample directly (rather than splitting the seeds=[...] list on commas) is robust
    // to the commas inside the WorldPos rendering.
    private static final Pattern SEED = Pattern.compile(
        "WorldPos\\[dimensionId=(-?\\d+), x=(-?\\d+), y=(-?\\d+), z=(-?\\d+)\\]"
            + " cas=(-?\\d+)\\u2192nms=(-?\\d+) \\((settled|dirty)\\)");
    private static final Pattern SEED_TASKS = Pattern.compile("seedTasks=(-?\\d+)");
    private static final Pattern MICROSTEPS = Pattern.compile("microsteps=(-?\\d+)");
    private static final Pattern MODIFIED = Pattern.compile("modified=(-?\\d+)");

    private FoliaDivergenceGrader() {
    }

    /** Verdict semantics mirror {@code scripts/perf-harness.sh}: a FAIL is a real signal. */
    public enum Verdict {
        PASS, FAIL, INCONCLUSIVE
    }

    /**
     * One seed's Folia-vs-Nebula sample. {@link #dirty()} is the divergence bit
     * ({@code casBefore != nmsAfter}).
     */
    public record SeedSample(WorldPos pos, int casBefore, int nmsAfter) {
        public boolean dirty() {
            return casBefore != nmsAfter;
        }
    }

    /** One {@code executeOwnedDag} invocation as parsed from a {@code CASCADE-DIAG} line. */
    public record DiagInvocation(int seedTasks, int microSteps, int modified, List<SeedSample> seeds) {
        public DiagInvocation {
            seeds = List.copyOf(seeds);
        }
    }

    /**
     * Parses every {@code CASCADE-DIAG} line from a server log into structured
     * invocations, preserving log order (which is invocation/tick order). Lines
     * without the marker are ignored, so a raw {@code server-run.log} can be fed
     * in directly.
     */
    public static List<DiagInvocation> parse(List<String> logLines) {
        if (logLines == null) {
            throw new IllegalArgumentException("logLines must not be null");
        }
        List<DiagInvocation> out = new ArrayList<>();
        for (String line : logLines) {
            if (line == null || !line.contains(DIAG_MARKER)) {
                continue;
            }
            List<SeedSample> seeds = new ArrayList<>();
            Matcher m = SEED.matcher(line);
            while (m.find()) {
                WorldPos pos = new WorldPos(
                    Integer.parseInt(m.group(1)),
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)));
                seeds.add(new SeedSample(pos,
                    Integer.parseInt(m.group(5)),
                    Integer.parseInt(m.group(6))));
            }
            out.add(new DiagInvocation(
                intField(SEED_TASKS, line),
                intField(MICROSTEPS, line),
                intField(MODIFIED, line),
                seeds));
        }
        return out;
    }

    /** Splits a multi-line log blob on newlines and delegates to {@link #parse(List)}. */
    public static List<DiagInvocation> parse(String log) {
        if (log == null) {
            throw new IllegalArgumentException("log must not be null");
        }
        return parse(List.of(log.split("\\R", -1)));
    }

    /**
     * Grades the residual (post-transient) dirty rate against {@code maxDirtyRate}.
     *
     * @param invocations   parsed diag invocations, in log/tick order
     * @param convergeWindow number of leading invocations to skip as the cold-CAS
     *                       transient (must be ≥ 0)
     * @param maxDirtyRate  the pass threshold on the residual dirty rate, in [0,1]
     * @return a report whose {@link DivergenceReport#verdict()} is INCONCLUSIVE
     *         when no graded samples remain, else PASS/FAIL against the threshold
     */
    public static DivergenceReport grade(List<DiagInvocation> invocations,
                                         int convergeWindow, double maxDirtyRate) {
        if (invocations == null) {
            throw new IllegalArgumentException("invocations must not be null");
        }
        if (convergeWindow < 0) {
            throw new IllegalArgumentException("convergeWindow must be >= 0");
        }
        if (maxDirtyRate < 0.0 || maxDirtyRate > 1.0) {
            throw new IllegalArgumentException("maxDirtyRate must be in [0,1]");
        }
        int graded = 0;
        int samples = 0;
        int dirty = 0;
        for (int i = 0; i < invocations.size(); i++) {
            if (i < convergeWindow) {
                continue;
            }
            graded++;
            for (SeedSample s : invocations.get(i).seeds()) {
                samples++;
                if (s.dirty()) {
                    dirty++;
                }
            }
        }
        return new DivergenceReport(invocations.size(), graded, samples, dirty,
            convergeWindow, maxDirtyRate);
    }

    private static int intField(Pattern p, String line) {
        Matcher m = p.matcher(line);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return -1;
    }

    /**
     * Grading result. The verdict is on the residual dirty rate <em>after</em> the
     * cold-CAS convergence window (see the class javadoc for why the transient is
     * excluded and why a low residual rate is necessary-but-not-sufficient for
     * correctness).
     */
    public record DivergenceReport(int totalInvocations, int gradedInvocations,
                                   int gradedSamples, int dirtySamples,
                                   int convergeWindow, double maxDirtyRate) {
        /** Residual dirty rate over graded samples; 0.0 when there are none. */
        public double dirtyRate() {
            return gradedSamples == 0 ? 0.0 : (double) dirtySamples / gradedSamples;
        }

        public Verdict verdict() {
            if (gradedSamples == 0) {
                return Verdict.INCONCLUSIVE;
            }
            return dirtyRate() <= maxDirtyRate ? Verdict.PASS : Verdict.FAIL;
        }

        public boolean passed() {
            return verdict() == Verdict.PASS;
        }
    }
}
