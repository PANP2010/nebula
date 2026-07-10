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
 * {@link DropperSlotGapGraderTest}; here we only assert the CLI delegates and maps
 * verdicts to the documented exit codes, against the byte-exact BE-DROPPER-SLOT log
 * format the plugin will emit (built via the real {@link DropperSlotFormatter}).
 */
class DropperSlotGapGraderCliTest {

    /**
     * A BE-DROPPER-SLOT line snapshotting {@code count} droppers where the first
     * {@code divergedCount} of them have a self-count gap of {@code gap} items.
     */
    private static String dropperLine(int tick, int count, int divergedCount, int gap) {
        List<DropperSlotGapGrader.DropperSlotSample> samples = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            WorldPos p = new WorldPos(0, i, 64, 2);
            int folia = 8;
            int nebula = i < divergedCount ? folia - gap : folia;
            samples.add(new DropperSlotGapGrader.DropperSlotSample(p, "DROPPER", nebula, folia));
        }
        return "[12:00:00] [org.nebula.plugin.NebulaPlugin] "
            + DropperSlotFormatter.format(tick, samples);
    }

    private record Run(int code, String out, String err) {}

    private static Run invoke(String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = DropperSlotGapGraderCli.run(args,
            new PrintStream(o, true, StandardCharsets.UTF_8),
            new PrintStream(e, true, StandardCharsets.UTF_8));
        return new Run(code, o.toString(StandardCharsets.UTF_8), e.toString(StandardCharsets.UTF_8));
    }

    private static Path writeLog(List<String> lines) throws IOException {
        Path f = Files.createTempFile("be-dropper-slot-cli-", ".log");
        f.toFile().deleteOnExit();
        Files.write(f, lines, StandardCharsets.UTF_8);
        return f;
    }

    @Test
    void passWhenAllDroppersTrackFoliaExactly() throws IOException {
        Path log = writeLog(List.of(dropperLine(100, 8, 0, 0)));
        Run r = invoke(log.toString());       // default tolerance 0
        assertEquals(DropperSlotGapGraderCli.EXIT_PASS, r.code());
        assertTrue(r.out().contains("Verdict: PASS"), r.out());
        assertTrue(r.out().contains("Droppers sampled:     8"), r.out());
        assertTrue(r.out().contains("Diverged droppers:    0"), r.out());
    }

    @Test
    void failOnAnyGapWithDefaultTolerance() throws IOException {
        Path log = writeLog(List.of(dropperLine(100, 8, 1, 3)));
        Run r = invoke(log.toString());
        assertEquals(DropperSlotGapGraderCli.EXIT_FAIL, r.code());
        assertTrue(r.out().contains("Verdict: FAIL"), r.out());
        assertTrue(r.out().contains("Diverged droppers:    1"), r.out());
        assertTrue(r.out().contains("Max self-count gap:   3 items"), r.out());
    }

    @Test
    void toleranceAllowsSmallGap() throws IOException {
        // gap of 2 items on 1 dropper; tolerance 2 must PASS (gap not > tolerance).
        Path log = writeLog(List.of(dropperLine(100, 4, 1, 2)));
        Run r = invoke(log.toString(), "2");
        assertEquals(DropperSlotGapGraderCli.EXIT_PASS, r.code());
        assertTrue(r.out().contains("Verdict: PASS"), r.out());
    }

    @Test
    void aggregatesAcrossMultipleSnapshots() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(dropperLine(100, 10, 0, 0));
        lines.add(dropperLine(200, 10, 1, 5));
        Path log = writeLog(lines);
        Run r = invoke(log.toString(), "0");
        assertEquals(DropperSlotGapGraderCli.EXIT_FAIL, r.code());
        assertTrue(r.out().contains("Dropper snapshots:    2"), r.out());
        assertTrue(r.out().contains("Droppers sampled:     20"), r.out());
    }

    @Test
    void inconclusiveWhenNoDropperLines() throws IOException {
        Path log = writeLog(List.of("[12:00:00] [Server] no diag here", "another line"));
        Run r = invoke(log.toString());
        assertEquals(DropperSlotGapGraderCli.EXIT_INCONCLUSIVE, r.code());
        assertTrue(r.out().contains("Verdict: INCONCLUSIVE"), r.out());
    }

    @Test
    void ignoresFurnaceTimerLines() throws IOException {
        // A BE-FURNACE-TIMER line must NOT be picked up by the dropper grader.
        String furnace = "[12:00:00] [org.nebula.plugin.NebulaPlugin] BE-FURNACE-TIMER: tick=1 "
            + "tracked=1 positions=[FURNACE WorldPos[dimensionId=0, x=0, y=64, z=0] "
            + "nebulaFuel=1580 foliaFuel=1580 nebulaCook=42 foliaCook=42]";
        Path log = writeLog(List.of(furnace));
        Run r = invoke(log.toString());
        assertEquals(DropperSlotGapGraderCli.EXIT_INCONCLUSIVE, r.code());
    }

    @Test
    void usageErrorOnNoArgs() {
        Run r = invoke();
        assertEquals(DropperSlotGapGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("usage:"), r.err());
    }

    @Test
    void usageErrorOnMissingFile() {
        Run r = invoke("/nonexistent/path/to/server-run.log");
        assertEquals(DropperSlotGapGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("cannot read log"), r.err());
    }

    @Test
    void usageErrorOnNonIntegerTolerance() throws IOException {
        Path log = writeLog(List.of(dropperLine(100, 1, 0, 0)));
        Run r = invoke(log.toString(), "notanint");
        assertEquals(DropperSlotGapGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("toleranceItems must be an integer"), r.err());
    }

    @Test
    void usageErrorOnNegativeTolerance() throws IOException {
        Path log = writeLog(List.of(dropperLine(100, 1, 0, 0)));
        Run r = invoke(log.toString(), "-1");
        assertEquals(DropperSlotGapGraderCli.EXIT_USAGE, r.code());
        assertTrue(r.err().contains("toleranceItems must be >= 0"), r.err());
    }
}
