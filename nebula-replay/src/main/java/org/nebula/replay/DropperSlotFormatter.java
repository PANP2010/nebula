package org.nebula.replay;

import org.nebula.core.state.WorldPos;

import java.util.List;
import java.util.Objects;

/**
 * Renders a {@code BE-DROPPER-SLOT} line body byte-exactly to the shape
 * {@link DropperSlotGapGrader#parse} consumes. This is the single source of truth for that
 * format: the plugin's dropper-slot emit calls this rather than hand-building the string,
 * so the producer and the parser cannot drift — the same anti-drift discipline as
 * {@link FurnaceTimerFormatter} and {@link BlockEntitySettledFormatter}.
 *
 * <h3>Why this exists as its own slice</h3>
 * The whole dropper-eject-gap signal is worthless if the emitted line does not match the
 * grader's regex — a subtly-off format makes {@code parse} silently return zero positions,
 * which grades {@code INCONCLUSIVE} (a fake "no dropper sampled") and wastes an entire live
 * Folia run. That is exactly the producer/consumer drift that is the project's defining
 * wound. Pinning the format here, with a round-trip test that feeds this formatter's output
 * straight back through the real {@link DropperSlotGapGrader#parse}, makes any future format
 * change fail a unit test instead of a live run.
 *
 * <p>The output is the message <em>body</em> only — it starts with the
 * {@link DropperSlotGapGrader#DROPPER_MARKER} and does NOT include a log timestamp or
 * logger-name prefix, because the plugin's {@code LOG.info(...)} supplies those. The parser
 * matches on the marker substring, so the prefix the logger prepends is irrelevant to
 * grading.
 *
 * <p>Each position is rendered as {@code <TYPE> <WorldPos.toString()> nebula=X folia=Y},
 * where {@code TYPE} is {@code DROPPER} or {@code DISPENSER} and {@code nebula}/{@code folia}
 * are the summed self-inventory item counts, and the positions are wrapped in
 * {@code positions=[...]} joined by {@code ", "} — identical in shape to
 * {@link BlockEntitySettledFormatter}.
 */
public final class DropperSlotFormatter {

    private DropperSlotFormatter() {
    }

    /**
     * Formats one dropper-slot snapshot into a grader-parseable {@code BE-DROPPER-SLOT}
     * line body. {@code tracked} is set to {@code positions.size()}.
     *
     * @param tick      the tick at which the snapshot was taken
     * @param positions the per-dropper eject samples, in emit order
     * @return the line body, e.g.
     *         {@code BE-DROPPER-SLOT: tick=100 tracked=1 positions=[DROPPER WorldPos[dimensionId=0, x=100, y=64, z=100] nebula=8 folia=8]}
     */
    public static String format(int tick, List<DropperSlotGapGrader.DropperSlotSample> positions) {
        Objects.requireNonNull(positions, "positions");
        StringBuilder sb = new StringBuilder();
        sb.append(DropperSlotGapGrader.DROPPER_MARKER)
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
     * {@code POSITION} regex is written against. The leading {@code TYPE} token
     * (DROPPER/DISPENSER) keeps the line shape symmetric with the other block-entity
     * formatters.
     */
    static String renderPosition(DropperSlotGapGrader.DropperSlotSample p) {
        return p.type() + " " + p.pos() + " nebula=" + p.nebula() + " folia=" + p.folia();
    }
}
