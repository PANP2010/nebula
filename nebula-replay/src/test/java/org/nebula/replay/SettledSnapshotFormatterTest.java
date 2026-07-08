package org.nebula.replay;

import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins {@link SettledSnapshotFormatter} against the ONLY property that matters: its
 * output must round-trip cleanly through the real {@link SettledDivergenceGrader#parse}.
 *
 * <p>The formatter exists so the plugin's quiescence emit and the grader's parser
 * cannot drift — the project's defining wound. A subtly-off format would make
 * {@code parse} silently return zero positions, grading a fake INCONCLUSIVE and
 * wasting a whole live Folia run. So every test here feeds the formatter's output
 * straight back through the production parser rather than asserting on a hand-built
 * expected string; that is what makes a future format change fail here instead of
 * on the server.
 */
class SettledSnapshotFormatterTest {

    private static final WorldPos A = new WorldPos(0, 1, -60, 2);
    private static final WorldPos B = new WorldPos(0, 17, -60, 2);
    private static final WorldPos C = new WorldPos(-1, -100, -59, -33);

    private static SettledDivergenceGrader.PositionSample sample(WorldPos p, int nebula, int folia) {
        return new SettledDivergenceGrader.PositionSample(p, nebula, folia);
    }

    /** Wraps the body in a log-line prefix like the plugin's {@code LOG.info} would. */
    private static String asLogLine(String body) {
        return "[12:00:00] [org.nebula.plugin.NebulaPlugin] " + body;
    }

    @Test
    void formattedLineRoundTripsThroughParser() {
        List<SettledDivergenceGrader.PositionSample> in = List.of(
            sample(A, 15, 15),
            sample(B, 0, 14),      // a diverged position must survive the round-trip
            sample(C, 0, 0));
        String body = SettledSnapshotFormatter.format(1234, in);

        List<SettledDivergenceGrader.SettledSnapshot> parsed =
            SettledDivergenceGrader.parse(asLogLine(body));

        assertEquals(1, parsed.size());
        SettledDivergenceGrader.SettledSnapshot snap = parsed.get(0);
        assertEquals(1234, snap.tick());
        assertEquals(3, snap.tracked());
        assertEquals(in, snap.positions());
    }

    @Test
    void divergedFlagSurvivesRoundTrip() {
        // The correctness bit the whole signal rests on: a nebula!=folia sample must
        // still read as diverged() after format -> parse.
        String body = SettledSnapshotFormatter.format(7, List.of(sample(B, 0, 15)));
        var snap = SettledDivergenceGrader.parse(asLogLine(body)).get(0);
        assertTrue(snap.positions().get(0).diverged());
        assertEquals(FoliaDivergenceGrader.Verdict.FAIL,
            SettledDivergenceGrader.grade(List.of(snap), 0.0).verdict());
    }

    @Test
    void allAgreeRoundTripsToPass() {
        String body = SettledSnapshotFormatter.format(100,
            List.of(sample(A, 15, 15), sample(B, 15, 15)));
        var snaps = SettledDivergenceGrader.parse(asLogLine(body));
        assertEquals(FoliaDivergenceGrader.Verdict.PASS,
            SettledDivergenceGrader.grade(snaps, 0.0).verdict());
    }

    @Test
    void tickIsCarriedThrough() {
        String body = SettledSnapshotFormatter.format(98765, List.of(sample(A, 1, 1)));
        assertEquals(98765, SettledDivergenceGrader.parse(asLogLine(body)).get(0).tick());
    }

    @Test
    void trackedCountEqualsPositionCount() {
        String body = SettledSnapshotFormatter.format(1,
            List.of(sample(A, 1, 1), sample(B, 2, 2), sample(C, 3, 3)));
        assertTrue(body.contains("tracked=3"), body);
        assertEquals(3, SettledDivergenceGrader.parse(asLogLine(body)).get(0).tracked());
    }

    @Test
    void emptyPositionsRoundTripsToInconclusive() {
        // No tracked positions -> the parser must still recognise a SETTLED-DIAG line
        // (so it is not mistaken for "never settled"), and grading is INCONCLUSIVE.
        String body = SettledSnapshotFormatter.format(1, List.of());
        assertTrue(body.contains("tracked=0"), body);
        assertTrue(body.contains("positions=[]"), body);
        var snaps = SettledDivergenceGrader.parse(asLogLine(body));
        assertEquals(1, snaps.size());
        assertTrue(snaps.get(0).positions().isEmpty());
        assertEquals(FoliaDivergenceGrader.Verdict.INCONCLUSIVE,
            SettledDivergenceGrader.grade(snaps, 0.0).verdict());
    }

    @Test
    void bodyStartsWithMarkerAndCarriesNoLogPrefix() {
        // The formatter emits the body only; the logger supplies timestamp/name.
        String body = SettledSnapshotFormatter.format(1, List.of(sample(A, 1, 1)));
        assertTrue(body.startsWith(SettledDivergenceGrader.SETTLED_MARKER), body);
    }

    @Test
    void rejectsNullPositions() {
        assertThrows(NullPointerException.class,
            () -> SettledSnapshotFormatter.format(1, null));
    }
}
