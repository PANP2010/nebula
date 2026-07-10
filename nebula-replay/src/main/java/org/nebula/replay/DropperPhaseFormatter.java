package org.nebula.replay;

import org.nebula.core.state.WorldPos;

import java.util.List;
import java.util.Objects;

/**
 * Renders a {@code BE-DROPPER-PHASE} line body byte-exactly to the shape
 * {@link DropperPhaseGrader#parse} consumes — the single source of truth for that format,
 * so the plugin's phase-probe emit and the parser cannot drift (the same anti-drift
 * discipline as {@link FurnacePhaseFormatter} and {@link DropperSlotFormatter}).
 *
 * <h3>What this line carries and why it differs from BE-DROPPER-SLOT</h3>
 * {@link DropperSlotFormatter} emits a single post-action snapshot (shadow CAS self count vs
 * Folia) and lets {@link DropperSlotGapGrader} measure the <em>magnitude</em> of the gap. That
 * gap came out a systematic {@code +1} (36ac531: Folia one item ahead of the shadow, both
 * stepping down in lockstep) — exactly what the observe-only eject action's per-tick step
 * would produce — but a post-action-only sample cannot tell a harmless one-step ORDERING lead
 * (the shadow samples one eject-step behind the Folia read, but steps at Folia's rate) apart
 * from a genuine RATE divergence (the shadow ejects faster than Folia, the double-ejector
 * signature). To classify it, this line carries THREE self counts, captured on one
 * region-thread pass:
 * <ul>
 *   <li>{@code foliaSelf} — Folia's authoritative summed self count (region-thread
 *       {@code readNmsInventoryCount}).</li>
 *   <li>{@code preSelf} — the CAS self count <em>after</em> {@code syncFromNms} rebased it to
 *       Folia but <em>before</em> the DAG eject action ran.</li>
 *   <li>{@code postSelf} — the CAS self count <em>after</em> the DAG eject action ran.</li>
 * </ul>
 * If {@code preSelf == foliaSelf} (the sync rebased CAS onto Folia exactly) and the whole
 * observed offset is {@code postSelf - preSelf} (the action's own single eject step), the
 * {@code +1} is a pure ordering artifact and the shadow tracks Folia at rate 1:1. A nonzero
 * {@code preSelf} gap means CAS diverged from Folia BEFORE the action — a genuine divergence
 * sync should have erased. See {@link DropperPhaseGrader}.
 *
 * <p>The output is the message <em>body</em> only — it starts with the
 * {@link DropperPhaseGrader#PHASE_MARKER} and does NOT include a log timestamp or
 * logger-name prefix, because the plugin's {@code LOG.info(...)} supplies those.
 */
public final class DropperPhaseFormatter {

    private DropperPhaseFormatter() {
    }

    /**
     * Formats one dropper-phase snapshot into a grader-parseable {@code BE-DROPPER-PHASE}
     * line body. {@code tracked} is set to {@code positions.size()}.
     *
     * @param tick      the tick at which the snapshot was taken
     * @param positions the per-dropper phase samples, in emit order
     * @return the line body, e.g. {@code BE-DROPPER-PHASE: tick=100 tracked=1
     *         positions=[DROPPER WorldPos[dimensionId=0, x=100, y=64, z=100] foliaSelf=8
     *         preSelf=8 postSelf=7]}
     */
    public static String format(int tick, List<DropperPhaseGrader.DropperPhaseSample> positions) {
        Objects.requireNonNull(positions, "positions");
        StringBuilder sb = new StringBuilder();
        sb.append(DropperPhaseGrader.PHASE_MARKER)
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
     * Renders a single sample as {@code <TYPE> <WorldPos> foliaSelf=.. preSelf=.. postSelf=..}.
     * Relies on {@link WorldPos}'s default record {@code toString}, which the grader's
     * {@code POSITION} regex is written against. The leading {@code TYPE} token
     * (DROPPER/DISPENSER) keeps the line shape symmetric with the other block-entity
     * formatters.
     */
    static String renderPosition(DropperPhaseGrader.DropperPhaseSample p) {
        return p.type() + " " + p.pos()
            + " foliaSelf=" + p.foliaSelf()
            + " preSelf=" + p.preSelf()
            + " postSelf=" + p.postSelf();
    }
}
