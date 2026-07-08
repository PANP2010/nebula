package org.nebula.replay;

import org.junit.jupiter.api.Test;
import org.nebula.core.state.WorldPos;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the thin CLI's I/O and exit-code mapping. The grading itself is covered by
 * {@link FoliaDivergenceGraderTest}; here we only assert the CLI delegates and maps
 * verdicts to the documented exit codes, byte-identical to the real log format.
 */
class FoliaDivergenceGraderCliTest {

    private static String diagLine(int cas, int nms) {
        WorldPos p = new WorldPos(0, 1, -60, 2);
        String rendered = "WorldPos[dimensionId=" + p.dimensionId() + ", x=" + p.x()
            + ", y=" + p.y() + ", z=" + p.z() + "]";
        String seed = rendered + " cas=" + cas + "→nms=" + nms
            + (cas == nms ? " (settled)" : " (dirty)");
        return "[12:00:00] [org.nebula.plugin.NebulaPlugin] CASCADE-DIAG: seedTasks=1"
            + " microsteps=1 modified=1 seeds=[" + seed + "]";
    }

    private record Run(int code, String out, String err) {}

    private static Run invoke(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = FoliaDivergenceGraderCli.run(args,
            new PrintStream(o, true, StandardCharsets.UTF_8),
            new PrintStream(e, true, StandardCharsets.UTF_8));
        return new Run(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    private static Path writeLog(List<String> lines) throws IOException {
        Path f = Files.createTempFile("diag-cli-", ".log");
        f.toFile().deleteOnExit();
        Files.write(f, lines, StandardCharsets.UTF_8);
        return f;
    }

    @Test
    void passWhenResidualDirtyRateLow() throws IOException {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            lines.add(diagLine(0, 15));   // cold transient (dirty)
        }
        for (int i = 0; i < 16; i++) {
            lines.add(diagLine(15, 15));  // settled
        }
        Path log = writeLog(lines);
        Run r = invoke(log.toString(), "4", "0.05");
        assertEquals(FoliaDivergenceGraderCli.EXIT_PASS, r.code());
        assertTrue(r.out().contains("Verdict: PASS"), r.out());
        assertTrue(r.out().contains("Seed samples graded: 16"), r.out());
    }

    @Test
    void failOnSustainedDivergence() throws IOException {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            lines.add(diagLine(5, 15));   // stays dirty every graded invocation
        }
        Path log = writeLog(lines);
        Run r = invoke(log.toString(), "4", "0.05");
        assertEquals(FoliaDivergenceGraderCli.EXIT_FAIL, r.code());
        assertTrue(r.out().contains("Verdict: FAIL"), r.out());
    }

    @Test
    void inconclusiveWhenNoDiagLines() throws IOException {
        Path log = writeLog(List.of("[12:00:00] [Server] no diag here", "another line"));
        Run r = invoke(log.toString());
        assertEquals(FoliaDivergenceGraderCli.EXIT_INCONCLUSIVE, r.code());
        assertTrue(r.out().contains("Verdict: INCONCLUSIVE"), r.out());
    }

    @Test
    void usesDefaultsWhenOnlyLogGiven() throws IOException {
        // 8-line default converge window leaves nothing to grade for a short log,
        // proving the default window (8) is applied when args omit it.
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            lines.add(diagLine(0, 15));
        }
        Path log = writeLog(lines);
        Run r = invoke(log.toString());
        assertEquals(FoliaDivergenceGraderCli.EXIT_INCONCLUSIVE, r.code());
        assertTrue(r.out().contains("Converge window:     8"), r.out());
    }

    @Test
    void usageErrorOnNoArgs() {
        Run r = invoke();
        assertEquals(FoliaDivergenceGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("usage:"), r.err());
    }

    @Test
    void usageErrorOnMissingFile() {
        Run r = invoke("/nonexistent/path/to/server-run.log");
        assertEquals(FoliaDivergenceGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("cannot read log"), r.err());
    }

    @Test
    void usageErrorOnNonNumericArgs() throws IOException {
        Path log = writeLog(List.of(diagLine(15, 15)));
        Run r = invoke(log.toString(), "notanint");
        assertEquals(FoliaDivergenceGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("convergeWindow must be an int"), r.err());
    }

    @Test
    void usageErrorOnOutOfRangeDirtyRate() throws IOException {
        Path log = writeLog(List.of(diagLine(15, 15)));
        Run r = invoke(log.toString(), "0", "1.5");
        assertEquals(FoliaDivergenceGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("maxDirtyRate"), r.err());
    }
}
