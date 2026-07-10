package org.nebula.replay;

import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PaperDiffReportTest {

    private static final WorldPos A = new WorldPos(0, 1, -60, 2);
    private static final WorldPos B = new WorldPos(0, 8, -60, 2);
    private static final WorldPos C = new WorldPos(0, 15, -60, 2);

    private static PaperDiffReport.PositionDiff agree(WorldPos p, int v) {
        return new PaperDiffReport.PositionDiff(p, v, v);
    }

    private static PaperDiffReport.PositionDiff diff(WorldPos p, int nebula, int paper) {
        return new PaperDiffReport.PositionDiff(p, nebula, paper);
    }

    @Test
    void allEqualSamplesMatchAndPass() {
        PaperDiffReport r = new PaperDiffReport(List.of(agree(A, 15), agree(B, 14), agree(C, 0)));
        assertEquals(3, r.total());
        assertEquals(3, r.matched());
        assertTrue(r.mismatches().isEmpty());
        assertTrue(r.allMatched());
        assertEquals("matched 3 / total 3", r.summaryLine());
        assertTrue(r.mismatchLines().isEmpty());
    }

    @Test
    void mismatchesAreCountedAndRenderedInOrder() {
        PaperDiffReport r = new PaperDiffReport(List.of(
            agree(A, 15),
            diff(B, 12, 13),   // shadow one behind
            diff(C, 0, 15)));  // shadow never propagated
        assertEquals(3, r.total());
        assertEquals(1, r.matched());
        assertFalse(r.allMatched());
        assertEquals("matched 1 / total 3", r.summaryLine());

        List<PaperDiffReport.PositionDiff> mism = r.mismatches();
        assertEquals(2, mism.size());
        assertEquals(B, mism.get(0).pos());
        assertEquals(C, mism.get(1).pos());

        List<String> lines = r.mismatchLines();
        assertEquals(2, lines.size());
        assertEquals(B + " nebula=12 paper=13", lines.get(0));
        assertEquals(C + " nebula=0 paper=15", lines.get(1));
    }

    @Test
    void emptyReportIsNotAPass() {
        // Nothing sampled proves nothing — mirrors the settled gate's INCONCLUSIVE.
        PaperDiffReport r = new PaperDiffReport(List.of());
        assertEquals(0, r.total());
        assertEquals(0, r.matched());
        assertFalse(r.allMatched());
        assertEquals("matched 0 / total 0", r.summaryLine());
    }

    @Test
    void singleMatchedSamplePasses() {
        PaperDiffReport r = new PaperDiffReport(List.of(agree(A, 7)));
        assertTrue(r.allMatched());
        assertEquals("matched 1 / total 1", r.summaryLine());
    }

    @Test
    void positionDiffMatchedBitAndRender() {
        assertTrue(agree(A, 9).matched());
        assertFalse(diff(A, 9, 8).matched());
        assertEquals(A + " nebula=9 paper=8", diff(A, 9, 8).render());
    }

    @Test
    void reportIsDefensivelyCopiedAndImmutable() {
        var mutable = new java.util.ArrayList<PaperDiffReport.PositionDiff>();
        mutable.add(agree(A, 1));
        PaperDiffReport r = new PaperDiffReport(mutable);
        mutable.add(agree(B, 2)); // must not affect the report
        assertEquals(1, r.total());
        assertThrows(UnsupportedOperationException.class, () -> r.diffs().add(agree(C, 3)));
    }

    @Test
    void nullDiffsRejected() {
        assertThrows(NullPointerException.class, () -> new PaperDiffReport(null));
    }
}
