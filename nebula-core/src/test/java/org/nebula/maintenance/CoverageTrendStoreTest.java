package org.nebula.maintenance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nebula.maintenance.CoverageTrendAnalyzer.CoverageSnapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CoverageTrendStore}: round-trip of a
 * {@link CoverageSnapshot} through the on-disk JSONL format.
 */
class CoverageTrendStoreTest {

    @Test
    void appendAndLoadRoundTrips(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("coverage.jsonl");
        CoverageTrendStore store = new CoverageTrendStore(file);

        CoverageSnapshot a = new CoverageSnapshot(
            Instant.parse("2026-07-05T10:00:00Z"), "1.21.4", 100, 30);
        CoverageSnapshot b = new CoverageSnapshot(
            Instant.parse("2026-07-12T10:00:00Z"), "1.21.4", 100, 40);

        assertTrue(store.append(a));
        assertTrue(store.append(b));

        List<CoverageSnapshot> loaded = store.load();
        assertEquals(2, loaded.size());
        assertEquals(a, loaded.get(0));
        assertEquals(b, loaded.get(1));
    }

    @Test
    void loadReturnsEmptyListForMissingFile(@TempDir Path tmp) {
        CoverageTrendStore store = new CoverageTrendStore(tmp.resolve("missing.jsonl"));
        assertTrue(store.load().isEmpty());
    }

    @Test
    void loadSkipsCommentLinesAndMalformedRows(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("coverage.jsonl");
        Files.writeString(file,
            "# Nebula coverage history — DO NOT EDIT\n"
            + "\n"
            + "{\"timestamp\":\"2026-07-05T10:00:00Z\",\"version\":\"1.21.4\",\"total\":100,\"annotated\":30}\n"
            + "{ this is not valid json }\n"
            + "{\"timestamp\":\"2026-07-12T10:00:00Z\",\"version\":\"1.21.4\",\"total\":100,\"annotated\":40}\n");
        CoverageTrendStore store = new CoverageTrendStore(file);
        List<CoverageSnapshot> loaded = store.load();
        assertEquals(2, loaded.size());
        assertEquals(30, loaded.get(0).annotatedMethods());
        assertEquals(40, loaded.get(1).annotatedMethods());
    }

    @Test
    void loadSkipsRowWhereAnnotatedExceedsTotal(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("coverage.jsonl");
        Files.writeString(file,
            "{\"timestamp\":\"2026-07-05T10:00:00Z\",\"version\":\"1.21.4\",\"total\":100,\"annotated\":30}\n"
            + "{\"timestamp\":\"2026-07-12T10:00:00Z\",\"version\":\"1.21.4\",\"total\":100,\"annotated\":200}\n");
        CoverageTrendStore store = new CoverageTrendStore(file);
        List<CoverageSnapshot> loaded = store.load();
        assertEquals(1, loaded.size());
        assertEquals(30, loaded.get(0).annotatedMethods());
    }

    @Test
    void appendCreatesParentDirectories(@TempDir Path tmp) {
        Path nested = tmp.resolve("a/b/c/coverage.jsonl");
        CoverageTrendStore store = new CoverageTrendStore(nested);
        assertTrue(store.append(new CoverageSnapshot(
            Instant.now(), "1.21.4", 10, 5)));
        assertTrue(Files.exists(nested));
    }
}
