package org.nebula.maintenance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P1.5.3d coverage trend store — persists
 * {@link CoverageTrendAnalyzer.CoverageSnapshot} history to a JSON file so
 * the trend has memory across server restarts.
 *
 * <p><b>File format.</b> A small, hand-rolled JSON Lines format (one
 * snapshot per line) — not full JSON, because we want forward-compatible
 * comments and we don't want to pull in a JSON library for one file.
 * The file is also designed to be diff-friendly: each line is a
 * self-contained object, so a Git diff of the history is a chronological
 * list of coverage observations.
 *
 * <pre>{@code
 * # Nebula coverage history — DO NOT EDIT
 * {"timestamp":"2026-07-05T10:00:00Z","version":"1.21.4","total":42,"annotated":18}
 * {"timestamp":"2026-07-12T10:00:00Z","version":"1.21.4","total":42,"annotated":20}
 * }</pre>
 *
 * <p>The store is intentionally fail-quiet: a missing or unreadable file
 * is treated as an empty history. The decay-target check
 * (see {@link AnnotationCoverageDashboard#meetsDecayTarget}) is the
 * authoritative health signal; a missing history just means "no trend
 * data yet" — not a hard failure.
 */
public final class CoverageTrendStore {

    private static final Pattern SNAPSHOT_LINE = Pattern.compile(
        "\\{\\s*\"timestamp\"\\s*:\\s*\"([^\"]+)\"\\s*,"
            + "\\s*\"version\"\\s*:\\s*\"([^\"]+)\"\\s*,"
            + "\\s*\"total\"\\s*:\\s*(\\d+)\\s*,"
            + "\\s*\"annotated\"\\s*:\\s*(\\d+)\\s*}");

    private final Path file;

    public CoverageTrendStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /** Read the history from disk; returns an empty list on any read error. */
    public List<CoverageTrendAnalyzer.CoverageSnapshot> load() {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<CoverageTrendAnalyzer.CoverageSnapshot> out = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(file);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                CoverageTrendAnalyzer.CoverageSnapshot snap = parse(trimmed);
                if (snap != null) out.add(snap);
            }
        } catch (IOException e) {
            return List.of();
        }
        return out;
    }

    /**
     * Append a snapshot to the file. The timestamp, version, total, and
     * annotated count are written as one JSON object on its own line.
     * Creates parent directories if they do not exist. Returns true on
     * success, false on any I/O error.
     */
    public boolean append(CoverageTrendAnalyzer.CoverageSnapshot snap) {
        Objects.requireNonNull(snap, "snap");
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            String line = String.format(
                "{\"timestamp\":\"%s\",\"version\":\"%s\",\"total\":%d,\"annotated\":%d}%n",
                snap.timestamp().toString(),
                snap.minecraftVersion(),
                snap.totalBridgeMethods(),
                snap.annotatedMethods());
            Files.writeString(
                file,
                line,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static CoverageTrendAnalyzer.CoverageSnapshot parse(String line) {
        Matcher m = SNAPSHOT_LINE.matcher(line);
        if (!m.matches()) return null;
        try {
            Instant ts = Instant.parse(m.group(1));
            String version = m.group(2);
            int total = Integer.parseInt(m.group(3));
            int annotated = Integer.parseInt(m.group(4));
            if (annotated > total) return null;
            return new CoverageTrendAnalyzer.CoverageSnapshot(
                ts, version, total, annotated);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
