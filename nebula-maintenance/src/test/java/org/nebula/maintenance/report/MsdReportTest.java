package org.nebula.maintenance.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nebula.maintenance.asm.BytecodeMethodDescriptor;
import org.nebula.maintenance.asm.DiffLevel;
import org.nebula.maintenance.asm.MethodDelta;
import org.nebula.maintenance.asm.MethodSignatureDeltaDetector.PerClassResult;
import org.nebula.maintenance.asm.NebulaRWAnnotationPatcher.PatchCandidate;
import org.nebula.maintenance.asm.NebulaRWAnnotationPatcher.PatchCandidate.Note;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MsdReport} (Phase 1.5, NEBULA-PATCH-2026-001 §14.3.5
 * 组件 A).
 *
 * <p>The report is a pure renderer; the tests build small per-class results
 * by hand and assert the textual output contains the expected markers and
 * summary line.
 */
class MsdReportTest {

    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final int PUBLIC = org.objectweb.asm.Opcodes.ACC_PUBLIC;

    private static BytecodeMethodDescriptor desc(String name, String descriptor, int access) {
        return new BytecodeMethodDescriptor(ENTITY, "Entity", name, descriptor, access, null);
    }

    private static MethodDelta newDelta(String name, String descriptor) {
        var d = desc(name, descriptor, PUBLIC);
        return new MethodDelta(ENTITY, name, Optional.empty(), Optional.of(d), DiffLevel.NEW, "");
    }

    private static MethodDelta removedDelta(String name, String descriptor) {
        var d = desc(name, descriptor, PUBLIC);
        return new MethodDelta(ENTITY, name, Optional.of(d), Optional.empty(), DiffLevel.REMOVED, "");
    }

    private static MethodDelta descriptorChangedDelta(String name, String oldDesc, String newDesc) {
        var oldD = desc(name, oldDesc, PUBLIC);
        var newD = desc(name, newDesc, PUBLIC);
        return new MethodDelta(ENTITY, name, Optional.of(oldD), Optional.of(newD),
            DiffLevel.DESCRIPTOR_CHANGED, "was " + oldDesc);
    }

    @Test
    void reportIncludesFileLabelAndMarkers() {
        var perClass = List.of(new PerClassResult(ENTITY, List.of(
            newDelta("customName", "(Ljava/lang/String;)V"),
            removedDelta("oldTick", "()V"))));
        var report = new MsdReport("bridge-classes.txt", perClass, List.of());

        String body = report.render();
        assertTrue(body.contains("=== MSD Report ==="), "header present");
        assertTrue(body.contains("File: bridge-classes.txt"));
        assertTrue(body.contains("Class: " + ENTITY));
        assertTrue(body.contains("+ public customName(Ljava/lang/String;)V"),
            "NEW method uses + marker");
        assertTrue(body.contains("- public oldTick()V"),
            "REMOVED method uses - marker");
    }

    @Test
    void reportRendersL1AndL2Markers() {
        var perClass = List.of(new PerClassResult(ENTITY, List.of(
            descriptorChangedDelta("getHealth", "()I", "(J)I"))));
        var body = new MsdReport("bridge-classes.txt", perClass, List.of()).render();

        assertTrue(body.contains("~ public getHealth(J)I"));
        assertTrue(body.contains("L1 - DESCRIPTOR CHANGED"));
        assertTrue(body.contains("was ()I"));
    }

    @Test
    void reportRendersPatchSuggestionBlock() {
        var delta = newDelta("customName", "(Ljava/lang/String;)V");
        PatchCandidate patch = new PatchCandidate(
            ENTITY, "customName", "(Ljava/lang/String;)V",
            DiffLevel.NEW,
            "@NebulaRW(readBlocks={\"<TODO: confirm>\"})",
            Note.HUMAN_REVIEW_REQUIRED,
            "L0 pure addition");
        var perClass = List.of(new PerClassResult(ENTITY, List.of(delta)));
        var body = new MsdReport("bridge-classes.txt", perClass, List.of(patch)).render();

        assertTrue(body.contains("Suggestion:"));
        assertTrue(body.contains("@NebulaRW"));
        assertTrue(body.contains("Reasoning: L0 pure addition"));
    }

    @Test
    void reportSummaryCountsAndAutoRate() {
        var perClass = List.of(new PerClassResult(ENTITY, List.of(
            newDelta("a", "()V"),
            newDelta("b", "()V"),
            descriptorChangedDelta("c", "()I", "(J)I"),
            removedDelta("d", "()V"),
            new MethodDelta(ENTITY, "e", Optional.of(desc("e", "()V", PUBLIC)),
                Optional.of(desc("e", "()V", org.objectweb.asm.Opcodes.ACC_PROTECTED)),
                DiffLevel.VISIBILITY_CHANGED, ""))));
        var body = new MsdReport("bridge-classes.txt", perClass, List.of()).render();

        // Counts.
        assertTrue(body.matches("(?s).*NEW\\s+:.*"));
        assertTrue(body.matches("(?s).*DESCRIPTOR_CHANGED\\s+:.*"));
        assertTrue(body.matches("(?s).*VISIBILITY_CHANGED\\s+:.*"));
        assertTrue(body.matches("(?s).*REMOVED\\s+:.*"));
        // Counts — format is "%-22s: %3d" so keys are left-padded to 22, then ": N".
        // %3d left-pads to 3 chars, so the count for N=2 is "  2" (2 spaces + digit).
        assertTrue(body.matches("(?s).*NEW\\s+:\\s+2.*"), "NEW count line");
        assertTrue(body.matches("(?s).*DESCRIPTOR_CHANGED\\s+:\\s+1.*"), "DESC count line");
        assertTrue(body.matches("(?s).*VISIBILITY_CHANGED\\s+:\\s+1.*"), "VISIB count line");
        assertTrue(body.matches("(?s).*REMOVED\\s+:\\s+1.*"), "REMOVED count line");
        // 2 NEW + 1 DESC = 3 auto-migrate, 5 total → 60.0%
        assertTrue(body.contains("Auto-migrate rate    : 3/5 = 60.0%"),
            "expected 60% auto-migrate rate");
    }

    @Test
    void emptyReportHandlesVacuousAutoRate() {
        var body = new MsdReport("empty.txt", List.of(), List.of()).render();
        assertTrue(body.contains("No changes detected."));
        assertTrue(body.contains("Auto-migrate rate    : 100.0% (vacuous)"));
        assertTrue(body.contains("Classes scanned      : 0"));
        assertTrue(body.contains("Patches drafted      : 0"));
    }

    @Test
    void writeToFileProducesReadableUtf8File(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("msd-report.txt");
        var perClass = List.of(new PerClassResult(ENTITY, List.of(newDelta("foo", "()V"))));
        var report = new MsdReport("bridge.txt", perClass, List.of());
        long bytes = report.writeToFile(out);
        assertEquals(Files.size(out), bytes);
        String content = Files.readString(out);
        assertTrue(content.contains("foo()V"));
    }
}