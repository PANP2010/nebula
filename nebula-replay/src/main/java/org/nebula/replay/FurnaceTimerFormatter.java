package org.nebula.replay;

import org.nebula.core.state.WorldPos;

import java.util.List;
import java.util.Objects;

/**
 * Renders a {@code BE-FURNACE-TIMER} line body byte-exactly to the shape
 * {@link FurnaceTimerGapGrader#parse} consumes. This is the single source of truth for
 * that format: the plugin's furnace-timer emit calls this rather than hand-building the
 * string, so the producer and the parser cannot drift — the same anti-drift discipline as
 * {@link BlockEntitySettledFormatter} and {@link SettledSnapshotFormatter}.
 *
 * <h3>Why this exists as its own slice</h3>
 * The whole furnace-timer-gap signal is worthless if the emitted line does not match the
 * grader's regex — a subtly-off format makes {@code parse} silently return zero positions,
 * which grades {@code INCONCLUSIVE} (a fake "no furnaces sampled") and wastes an entire live
 * Folia run. That is exactly the producer/consumer drift that is the project's defining
 * wound. Pinning the format here, with a round-trip test that feeds this formatter's output
 * straight back through the real {@link FurnaceTimerGapGrader#parse}, makes any future
 * format change fail a unit test instead of a live run.
 *
 * <p>The output is the message <em>body</em> only — it starts with the
 * {@link FurnaceTimerGapGrader#TIMER_MARKER} and does NOT include a log timestamp or
 * logger-name prefix, because the plugin's {@code LOG.info(...)} supplies those. The parser
 * matches on the marker substring, so the prefix the logger prepends is irrelevant to
 * grading.
 *
 * <p>Each position is rendered as {@code <TYPE> <WorldPos.toString()> nebulaFuel=A
 * foliaFuel=B nebulaCook=C foliaCook=D} and the positions are wrapped in
 * {@code positions=[...]} joined by {@code ", "}. Both timer axes are emitted for each
 * furnace so the grader can measure the fuel-time and cook-progress gaps independently — a
 * furnace can track Folia on one axis while drifting on the other, and collapsing them to a
 * single number would hide that.
 */
public final class FurnaceTimerFormatter {

    private FurnaceTimerFormatter() {
    }

    /**
     * Formats one furnace-timer snapshot into a grader-parseable {@code BE-FURNACE-TIMER}
     * line body. {@code tracked} is set to {@code positions.size()}.
     *
     * @param tick      the tick at which the snapshot was taken
     * @param positions the per-furnace timer samples, in emit order
     * @return the line body, e.g.
     *         {@code BE-FURNACE-TIMER: tick=100 tracked=1 positions=[FURNACE WorldPos[dimensionId=0, x=0, y=64, z=0] nebulaFuel=1580 foliaFuel=1580 nebulaCook=42 foliaCook=42]}
     */
    public static String format(int tick, List<FurnaceTimerGapGrader.FurnaceTimerSample> positions) {
        Objects.requireNonNull(positions, "positions");
        StringBuilder sb = new StringBuilder();
        sb.append(FurnaceTimerGapGrader.TIMER_MARKER)
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
     * Renders a single sample as {@code FURNACE <WorldPos> nebulaFuel=A foliaFuel=B
     * nebulaCook=C foliaCook=D}. Relies on {@link WorldPos}'s default record
     * {@code toString}, which the grader's {@code POSITION} regex is written against. The
     * leading {@code FURNACE} token keeps the line shape symmetric with
     * {@link BlockEntitySettledFormatter}'s {@code <TYPE> WorldPos ...} form.
     */
    static String renderPosition(FurnaceTimerGapGrader.FurnaceTimerSample p) {
        return "FURNACE " + p.pos()
            + " nebulaFuel=" + p.nebulaFuel() + " foliaFuel=" + p.foliaFuel()
            + " nebulaCook=" + p.nebulaCook() + " foliaCook=" + p.foliaCook();
    }
}
