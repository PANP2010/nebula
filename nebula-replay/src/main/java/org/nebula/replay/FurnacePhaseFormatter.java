package org.nebula.replay;

import org.nebula.core.state.WorldPos;

import java.util.List;
import java.util.Objects;

/**
 * Renders a {@code BE-FURNACE-PHASE} line body byte-exactly to the shape
 * {@link FurnacePhaseGrader#parse} consumes — the single source of truth for that format,
 * so the plugin's phase-probe emit and the parser cannot drift (the same anti-drift
 * discipline as {@link FurnaceTimerFormatter} and {@link BlockEntitySettledFormatter}).
 *
 * <h3>What this line carries and why it differs from BE-FURNACE-TIMER</h3>
 * {@link FurnaceTimerFormatter} emits a single post-action snapshot (shadow CAS vs Folia)
 * and lets {@link FurnaceTimerGapGrader} measure the <em>magnitude</em> of the gap. That
 * gap came out a systematic {@code +1} on cook and {@code -1} on fuel (800815b), which is
 * exactly the observe-only action's per-tick step — but a post-action-only sample cannot
 * tell a harmless one-step ORDERING lead (the shadow samples one action-step ahead of the
 * Folia read, but advances at Folia's rate) apart from a genuine RATE divergence (the shadow
 * advances faster than Folia). To classify it, this line carries THREE timers per axis,
 * captured on one region-thread pass:
 * <ul>
 *   <li>{@code folia*} — Folia's authoritative timer read (region-thread
 *       {@code readNmsFurnaceTimers}).</li>
 *   <li>{@code pre*} — the CAS timer <em>after</em> {@code syncFromNms} rebased it to Folia
 *       but <em>before</em> the DAG furnace action ran.</li>
 *   <li>{@code post*} — the CAS timer <em>after</em> the DAG furnace action advanced it.</li>
 * </ul>
 * If {@code pre* == folia*} (the sync rebased CAS onto Folia exactly) and the whole observed
 * offset is {@code post* - pre*} (the action's own single step), the {@code +1}/{@code -1} is
 * a pure ordering artifact and the shadow tracks Folia at rate 1:1. A nonzero {@code pre*}
 * gap means CAS diverged from Folia BEFORE the action — a genuine divergence sync should have
 * erased. See {@link FurnacePhaseGrader}.
 *
 * <p>The output is the message <em>body</em> only — it starts with the
 * {@link FurnacePhaseGrader#PHASE_MARKER} and does NOT include a log timestamp or
 * logger-name prefix, because the plugin's {@code LOG.info(...)} supplies those.
 */
public final class FurnacePhaseFormatter {

    private FurnacePhaseFormatter() {
    }

    /**
     * Formats one furnace-phase snapshot into a grader-parseable {@code BE-FURNACE-PHASE}
     * line body. {@code tracked} is set to {@code positions.size()}.
     *
     * @param tick      the tick at which the snapshot was taken
     * @param positions the per-furnace phase samples, in emit order
     * @return the line body, e.g. {@code BE-FURNACE-PHASE: tick=100 tracked=1
     *         positions=[FURNACE WorldPos[dimensionId=0, x=8, y=-49, z=8] foliaFuel=1332
     *         foliaCook=42 preFuel=1332 preCook=42 postFuel=1331 postCook=43]}
     */
    public static String format(int tick, List<FurnacePhaseGrader.FurnacePhaseSample> positions) {
        Objects.requireNonNull(positions, "positions");
        StringBuilder sb = new StringBuilder();
        sb.append(FurnacePhaseGrader.PHASE_MARKER)
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
     * Renders a single sample as {@code FURNACE <WorldPos> foliaFuel=.. foliaCook=..
     * preFuel=.. preCook=.. postFuel=.. postCook=..}. Relies on {@link WorldPos}'s default
     * record {@code toString}, which the grader's {@code POSITION} regex is written against.
     */
    static String renderPosition(FurnacePhaseGrader.FurnacePhaseSample p) {
        return "FURNACE " + p.pos()
            + " foliaFuel=" + p.foliaFuel() + " foliaCook=" + p.foliaCook()
            + " preFuel=" + p.preFuel() + " preCook=" + p.preCook()
            + " postFuel=" + p.postFuel() + " postCook=" + p.postCook();
    }
}
