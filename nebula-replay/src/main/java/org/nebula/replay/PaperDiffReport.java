package org.nebula.replay;

import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure compare/format model for the <em>single-thread differential</em> (B9 D3):
 * per position, Nebula's CAS-computed shadow power ({@code nebula=N}) against the
 * authoritative block power a <strong>single-thread Paper</strong> server resolved
 * ({@code paper=M}). This is the load-bearing probe for the claim the whole project
 * rests on — <em>a multi-threaded (DAG-parallel) server produces the same result as
 * a single-threaded vanilla MC server</em> — because Paper is the actual single-thread
 * oracle that claim names (Folia is itself multi-threaded, so every prior live check
 * was Nebula-vs-Nebula or Nebula-vs-Folia, never against the single-thread authority).
 *
 * <h3>Why this is a distinct model, not a reuse of {@link SettledDivergenceGrader}</h3>
 * {@code SettledDivergenceGrader} grades a divergence <em>rate</em> against a threshold
 * for the Folia-vs-Nebula settled gate. The Paper differential is a stricter, simpler
 * verdict: at settled quiescence the two must be <strong>exactly</strong> equal, so the
 * signal is {@code matched == total} (any mismatch is a real correctness finding, not a
 * boundary-lag excuse). The per-mismatch surface — {@code pos: nebula=N paper=M} — is
 * what an operator reads to localise a divergence, so it is modelled here rather than as
 * a rate. Keeping the compare/format pure (this class) from the live I/O (the plugin's
 * main-thread NMS read) is the same producer/consumer discipline
 * {@link SettledSnapshotFormatter} enforces: the format cannot drift from what the tests
 * pin, and the verdict is unit-testable with injected values before any live Paper run.
 *
 * <h3>Read legality (why Paper, not Folia)</h3>
 * The {@code paper=M} value is an authoritative block read, which is legal on Paper's
 * main thread but NPEs off the owning region thread on Folia. The command that produces
 * these samples therefore self-guards to {@code isFoliaServer() == false}; this pure
 * model performs no I/O and is host-agnostic.
 */
public final class PaperDiffReport {

    /**
     * One position's differential sample. {@link #matched()} is the correctness bit:
     * at settled quiescence the shadow must equal the single-thread authority exactly.
     */
    public record PositionDiff(WorldPos pos, int nebula, int paper) {
        public PositionDiff {
            Objects.requireNonNull(pos, "pos");
        }

        public boolean matched() {
            return nebula == paper;
        }

        /** Renders {@code <WorldPos> nebula=N paper=M} — the per-mismatch operator surface. */
        public String render() {
            return pos + " nebula=" + nebula + " paper=" + paper;
        }
    }

    private final List<PositionDiff> diffs;

    public PaperDiffReport(List<PositionDiff> diffs) {
        Objects.requireNonNull(diffs, "diffs");
        this.diffs = List.copyOf(diffs);
    }

    /** All sampled positions, in the order supplied. */
    public List<PositionDiff> diffs() {
        return diffs;
    }

    /** Total positions sampled. */
    public int total() {
        return diffs.size();
    }

    /** Positions where {@code nebula == paper}. */
    public int matched() {
        int m = 0;
        for (PositionDiff d : diffs) {
            if (d.matched()) {
                m++;
            }
        }
        return m;
    }

    /** The positions where the shadow disagrees with the single-thread authority. */
    public List<PositionDiff> mismatches() {
        List<PositionDiff> out = new ArrayList<>();
        for (PositionDiff d : diffs) {
            if (!d.matched()) {
                out.add(d);
            }
        }
        return out;
    }

    /**
     * The differential verdict. {@code true} only when every sampled position matched
     * <em>and at least one position was sampled</em> — an empty report is NOT a pass
     * (nothing was proven; the caller must register components / run {@code /nebula scan}
     * first, exactly as the settled gate treats a zero-sample run as INCONCLUSIVE).
     */
    public boolean allMatched() {
        return !diffs.isEmpty() && matched() == total();
    }

    /** {@code matched X / total Y} — the final summary line. */
    public String summaryLine() {
        return "matched " + matched() + " / total " + total();
    }

    /** One {@code <WorldPos> nebula=N paper=M} line per mismatch, in sample order. */
    public List<String> mismatchLines() {
        List<String> out = new ArrayList<>();
        for (PositionDiff d : mismatches()) {
            out.add(d.render());
        }
        return out;
    }
}
