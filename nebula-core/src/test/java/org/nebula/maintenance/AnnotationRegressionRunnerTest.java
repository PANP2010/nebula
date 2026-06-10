package org.nebula.maintenance;

import org.junit.jupiter.api.Test;
import org.nebula.maintenance.AnnotationRegressionRunner.RegressionReport;
import org.nebula.maintenance.AnnotationRegressionRunner.Status;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the annotation regression runner (NEBULA-PATCH-2026-001 §变更一 组件 B),
 * including against real decompiled MC 1.21.4 source fixtures.
 */
class AnnotationRegressionRunnerTest {

    // ── Real-source integrity check ───────────────────────────────────────────

    /** Loads all bundled decompiled redstone fixtures into one signature set. */
    private static List<MethodSignature> realRedstoneSignatures() throws IOException {
        // Include DiodeBlock: RepeaterBlock/ComparatorBlock inherit tick() etc.
        // from it, so a faithful integrity scan must cover the class hierarchy.
        String[] fixtures = {
            "RepeaterBlock.java", "DiodeBlock.java", "RedStoneWireBlock.java",
            "RedstoneTorchBlock.java", "ComparatorBlock.java"
        };
        List<MethodSignature> all = new ArrayList<>();
        for (String f : fixtures) {
            try (InputStream in = AnnotationRegressionRunnerTest.class.getResourceAsStream("/decompiled/" + f)) {
                assertNotNull(in, "fixture must be present: " + f);
                all.addAll(JavaSignatureExtractor.extract(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            }
        }
        return all;
    }

    @Test
    void realAnnotatedRedstoneMethodsArePresent() throws IOException {
        // Real method references that must still exist in decompiled MC 1.21.4 —
        // otherwise the annotation would silently fail (the §14.3.5 组件 B
        // guarantee). Inherited tick() is referenced at its declaring class
        // (DiodeBlock), which is the correct anchor for the integrity check.
        List<String> annotationRefs = List.of(
            "DiodeBlock.tick",                    // inherited by Repeater/Comparator
            "RepeaterBlock.getDelay",
            "RepeaterBlock.isLocked",
            "RedStoneWireBlock.neighborChanged",
            "RedStoneWireBlock.getBlockSignal",
            "RedstoneTorchBlock.tick",
            "ComparatorBlock.getInputSignal"
        );

        RegressionReport report = AnnotationRegressionRunner.run(annotationRefs, realRedstoneSignatures());
        assertTrue(report.passed(),
            "all annotated redstone methods must exist in MC 1.21.4: " + report.summary()
                + " failures=" + report.failures());
    }

    @Test
    void inheritedMethodNotOnLeafClassIsFlaggedMissing() {
        // A real finding this runner surfaces: RepeaterBlock.tick does NOT exist
        // on RepeaterBlock — tick() is declared on the parent DiodeBlock. An
        // annotation referencing the leaf class by name would silently fail at
        // the source level, so the runner flags it MISSING. The maintenance
        // toolchain must resolve inherited methods to their declaring class.
        List<MethodSignature> repeaterOnly = JavaSignatureExtractor.extract(
            "package net.minecraft.world.level.block; public class RepeaterBlock extends DiodeBlock {"
                + " protected int getDelay(BlockState s) { return 1; } }");
        RegressionReport report = AnnotationRegressionRunner.run(
            List.of("RepeaterBlock.tick"), repeaterOnly);
        assertEquals(1, report.countOf(Status.MISSING));
    }

    @Test
    void renamedOrRemovedMethodIsFlaggedMissing() throws IOException {
        // A reference to a method that does NOT exist (simulating an upstream
        // rename) must be reported MISSING so CI fails instead of silently
        // accepting a dead annotation.
        List<String> refs = List.of(
            "DiodeBlock.tick",                    // present (inherited base)
            "RepeaterBlock.tickRedstoneMagic");   // does not exist anywhere

        RegressionReport report = AnnotationRegressionRunner.run(refs, realRedstoneSignatures());
        assertFalse(report.passed());
        assertEquals(1, report.countOf(Status.MISSING));
        assertEquals(1, report.countOf(Status.PRESENT));
        assertEquals("RepeaterBlock.tickRedstoneMagic", report.failures().get(0).annotationRef());
    }

    @Test
    void overloadedMethodIsFlaggedAmbiguous() throws IOException {
        // RedStoneWireBlock has two getConnectingSide overloads, so a bare
        // reference is AMBIGUOUS — a human must confirm which overload.
        List<String> refs = List.of("RedStoneWireBlock.getConnectingSide");
        RegressionReport report = AnnotationRegressionRunner.run(refs, realRedstoneSignatures());
        assertEquals(1, report.countOf(Status.AMBIGUOUS));
        assertTrue(report.failures().get(0).matchCount() >= 2);
    }

    // ── Synthetic logic ───────────────────────────────────────────────────────

    private static MethodSignature m(String cls, String name) {
        return new MethodSignature("net.minecraft." + cls, name, "void", List.of(), List.of("public"));
    }

    @Test
    void presentMethodPasses() {
        RegressionReport r = AnnotationRegressionRunner.run(
            List.of("Foo.bar"), List.of(m("Foo", "bar")));
        assertTrue(r.passed());
        assertEquals(Status.PRESENT, r.results().get(0).status());
    }

    @Test
    void summaryReportsCounts() {
        List<MethodSignature> sigs = List.of(m("Foo", "a"), m("Foo", "dup"), m("Foo", "dup"));
        RegressionReport r = AnnotationRegressionRunner.run(
            List.of("Foo.a", "Foo.gone", "Foo.dup"), sigs);
        assertEquals(1, r.countOf(Status.PRESENT));
        assertEquals(1, r.countOf(Status.MISSING));
        assertEquals(1, r.countOf(Status.AMBIGUOUS));
        assertTrue(r.summary().contains("FAIL"));
    }

    @Test
    void distinctDeduplicatesRefs() {
        assertEquals(List.of("A.x", "A.y"),
            AnnotationRegressionRunner.distinct(List.of("A.x", "A.y", "A.x")));
    }

    @Test
    void inheritanceAwareResolutionFindsInheritedMethod() {
        // tick() is declared on Parent; Leaf inherits it. A "Leaf.tick" ref
        // resolves only when the superclass map links Leaf → Parent.
        List<MethodSignature> sigs = List.of(m("Parent", "tick"), m("Leaf", "ownMethod"));
        java.util.Map<String, String> supers = java.util.Map.of("Leaf", "Parent");

        // Without the hierarchy: MISSING.
        assertEquals(Status.MISSING,
            AnnotationRegressionRunner.run(List.of("Leaf.tick"), sigs).results().get(0).status());
        // With the hierarchy: resolves to Parent.tick → PRESENT.
        assertEquals(Status.PRESENT,
            AnnotationRegressionRunner.run(List.of("Leaf.tick"), sigs, supers).results().get(0).status());
    }

    @Test
    void inheritanceResolutionIsCycleSafe() {
        // A pathological A→B→A cycle must not loop forever; method simply MISSING.
        List<MethodSignature> sigs = List.of(m("A", "x"));
        java.util.Map<String, String> supers = java.util.Map.of("B", "C", "C", "B");
        assertEquals(Status.MISSING,
            AnnotationRegressionRunner.run(List.of("B.nope"), sigs, supers).results().get(0).status());
    }
}
