package org.nebula.replay;

import org.nebula.core.state.WorldPos;

import java.util.List;
import java.util.Objects;

/**
 * Renders a {@code BE-SETTLED} line body byte-exactly to the shape
 * {@link BlockEntitySettledGrader#parse} consumes. This is the single source of truth
 * for that format: the plugin's quiescence emit calls this rather than hand-building
 * the string, so the producer and the parser cannot drift.
 *
 * <h3>Why this exists as its own slice</h3>
 * The whole settled-state signal is worthless if the emitted line does not match the
 * grader's regex — a subtly-off format makes {@code parse} silently return zero
 * positions, which grades {@code INCONCLUSIVE} (a fake "never settled") and wastes an
 * entire live Folia run. That is exactly the producer/consumer drift that is the
 * project's defining wound. Pinning the format here, with a round-trip test that feeds
 * this formatter's output straight back through the real
 * {@link BlockEntitySettledGrader#parse}, makes any future format change fail a unit
 * test instead of a live run. This mirrors {@link SettledSnapshotFormatter} on the
 * redstone path.
 *
 * <p>The output is the message <em>body</em> only — it starts with the
 * {@link BlockEntitySettledGrader#SETTLED_MARKER} and does NOT include a log timestamp
 * or logger-name prefix, because the plugin's {@code LOG.info(...)} supplies those.
 * The parser matches on the marker substring, so the prefix the logger prepends is
 * irrelevant to grading.
 *
 * <p>Each position is rendered as {@code <TYPE> <WorldPos.toString()> nebula=X folia=Y}
 * and the positions are wrapped in {@code positions=[...]} joined by {@code ", "} —
 * identical to {@code List&lt;String&gt;.toString()}. {@code tracked} is the position
 * count. The leading {@code TYPE} token (e.g. {@code HOPPER}, {@code FURNACE})
 * distinguishes this line from the redstone {@code SETTLED-DIAG} format, where each
 * position starts directly with {@code WorldPos[...]}.
 */
public final class BlockEntitySettledFormatter {

    private BlockEntitySettledFormatter() {
    }

    /**
     * Formats one quiescence snapshot into a grader-parseable {@code BE-SETTLED} line
     * body. {@code tracked} is set to {@code positions.size()}.
     *
     * @param tick      the tick at which the snapshot was taken
     * @param positions the per-block-entity settled samples, in emit order
     * @return the line body, e.g.
     *         {@code BE-SETTLED: tick=100 tracked=1 positions=[HOPPER WorldPos[dimensionId=0, x=0, y=64, z=0] nebula=48 folia=48]}
     */
    public static String format(int tick, List<BlockEntitySettledGrader.BlockEntitySample> positions) {
        Objects.requireNonNull(positions, "positions");
        StringBuilder sb = new StringBuilder();
        sb.append(BlockEntitySettledGrader.SETTLED_MARKER)
            .append(" tick=").append(tick)
            .append(" tracked=").append(positions.size())
            .append(" positions=[");
        for (int i = 0; i < positions.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(renderPosition(positions.get(i)));
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * Renders a single sample as {@code <TYPE> <WorldPos> nebula=X folia=Y}. Relies on
     * {@link WorldPos}'s default record {@code toString}, which the grader's
     * {@code POSITION} regex is written against.
     */
    static String renderPosition(BlockEntitySettledGrader.BlockEntitySample p) {
        return p.type() + " " + p.pos() + " nebula=" + p.nebula() + " folia=" + p.folia();
    }
}
