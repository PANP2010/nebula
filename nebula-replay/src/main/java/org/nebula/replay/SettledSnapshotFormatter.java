package org.nebula.replay;

import org.nebula.core.state.WorldPos;

import java.util.List;
import java.util.Objects;

/**
 * Renders a {@code SETTLED-DIAG} line body byte-exactly to the shape
 * {@link SettledDivergenceGrader#parse} consumes. This is the single source of
 * truth for that format: the plugin's quiescence emit calls this rather than
 * hand-building the string, so the producer and the parser cannot drift.
 *
 * <h3>Why this exists as its own slice</h3>
 * The whole settled-state signal is worthless if the emitted line does not match
 * the grader's regex — a subtly-off format makes {@code parse} silently return
 * zero positions, which grades {@code INCONCLUSIVE} (a fake "circuit never
 * settled") and wastes an entire live Folia run. That is the settled-state twin
 * of the divergence tautology the grader's javadoc warns about, and it is exactly
 * the kind of producer/consumer drift that is the project's defining wound.
 * Pinning the format here, with a round-trip test that feeds this formatter's
 * output straight back through the real {@link SettledDivergenceGrader#parse},
 * makes any future format change fail a unit test instead of a live run.
 *
 * <p>The output is the message <em>body</em> only — it starts with the
 * {@link SettledDivergenceGrader#SETTLED_MARKER} and does NOT include a log
 * timestamp or logger-name prefix, because the plugin's {@code LOG.info(...)}
 * supplies those (mirroring how the {@code CASCADE-DIAG} emit is built). The
 * parser matches on the marker substring, so the prefix the logger prepends is
 * irrelevant to grading.
 *
 * <p>Each position is rendered as {@code <WorldPos.toString()> nebula=X folia=Y}
 * and the positions are wrapped in {@code positions=[...]} joined by {@code ", "}
 * — identical to {@code List&lt;String&gt;.toString()}, which is how the grader's
 * own tests build their fixtures. {@code tracked} is the position count.
 */
public final class SettledSnapshotFormatter {

    private SettledSnapshotFormatter() {
    }

    /**
     * Formats one quiescence snapshot into a grader-parseable {@code SETTLED-DIAG}
     * line body. {@code tracked} is set to {@code positions.size()}.
     *
     * @param tick      the tick at which the snapshot was taken
     * @param positions the per-position settled samples, in emit order
     * @return the line body, e.g.
     *         {@code SETTLED-DIAG: tick=100 tracked=2 positions=[WorldPos[dimensionId=0, x=1, y=-60, z=2] nebula=15 folia=15, ...]}
     */
    public static String format(int tick, List<SettledDivergenceGrader.PositionSample> positions) {
        Objects.requireNonNull(positions, "positions");
        StringBuilder sb = new StringBuilder();
        sb.append(SettledDivergenceGrader.SETTLED_MARKER)
            .append(" tick=").append(tick)
            .append(" tracked=").append(positions.size())
            .append(" positions=[");
        for (int i = 0; i < positions.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            SettledDivergenceGrader.PositionSample p = positions.get(i);
            sb.append(renderPosition(p));
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Renders a single position sample as {@code <WorldPos> nebula=X folia=Y}.
     * Relies on {@link WorldPos}'s default record {@code toString}, which the
     * grader's {@code POSITION} regex is written against.
     */
    static String renderPosition(SettledDivergenceGrader.PositionSample p) {
        return p.pos() + " nebula=" + p.nebula() + " folia=" + p.folia();
    }
}
