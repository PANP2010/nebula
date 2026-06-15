package org.nebula.maintenance;

import org.junit.jupiter.api.Test;
import org.nebula.maintenance.SignatureDiffer.ClassifiedChange;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the Method Signature change Detector (NEBULA-PATCH-2026-001 §变更一)
 * against a <em>real</em> decompiled Minecraft 1.21.4 source fixture
 * (RepeaterBlock.java, Mojmaps), plus synthetic before/after diffs.
 */
class SignatureDifferTest {

    // ── Extractor against real decompiled Mojmaps output ──────────────────────

    private static String repeaterBlockSource() throws IOException {
        try (InputStream in = SignatureDifferTest.class
                .getResourceAsStream("/decompiled/RepeaterBlock.java")) {
            assertNotNull(in, "RepeaterBlock fixture must be on the test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void extractsOwnerClassFromRealSource() throws IOException {
        String owner = JavaSignatureExtractor.ownerClassOf(repeaterBlockSource());
        assertEquals("net.minecraft.world.level.block.RepeaterBlock", owner);
    }

    @Test
    void extractsRealMethodSignatures() throws IOException {
        List<MethodSignature> sigs = JavaSignatureExtractor.extract(repeaterBlockSource());

        // Spot-check methods we know are in RepeaterBlock 1.21.4.
        assertTrue(hasMethod(sigs, "getDelay"), "should find getDelay");
        assertTrue(hasMethod(sigs, "isLocked"), "should find isLocked");
        assertTrue(hasMethod(sigs, "animateTick"), "should find animateTick");
        assertTrue(hasMethod(sigs, "sideInputDiodesOnly"), "should find sideInputDiodesOnly");
        // A multi-line signature (updateShape spans several lines in the source).
        assertTrue(hasMethod(sigs, "updateShape"), "should find the multi-line updateShape");
        // Constructors must NOT be extracted as methods.
        assertFalse(hasMethod(sigs, "RepeaterBlock"), "constructor must not be a method");
    }

    @Test
    void parsesParameterTypesCorrectly() throws IOException {
        List<MethodSignature> sigs = JavaSignatureExtractor.extract(repeaterBlockSource());
        MethodSignature getDelay = find(sigs, "getDelay");
        assertNotNull(getDelay);
        // protected int getDelay(final BlockState state)
        assertEquals("int", getDelay.returnType());
        assertEquals(List.of("BlockState"), getDelay.paramTypes());
        assertTrue(getDelay.modifiers().contains("protected"));

        MethodSignature isLocked = find(sigs, "isLocked");
        // public boolean isLocked(LevelReader level, BlockPos pos, BlockState state)
        assertEquals(List.of("LevelReader", "BlockPos", "BlockState"), isLocked.paramTypes());
    }

    @Test
    void parsesGenericParameterWithoutSplittingOnComma() {
        // createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder)
        // The comma inside <> must not split into two params.
        List<MethodSignature> sigs = JavaSignatureExtractor.extract(
            "package p; public class C {"
                + " protected void foo(final StateDefinition.Builder<Block, BlockState> b) {} }");
        MethodSignature foo = find(sigs, "foo");
        assertNotNull(foo);
        assertEquals(1, foo.paramTypes().size(), "generic comma must not split the param");
        assertEquals("StateDefinition.Builder<Block, BlockState>", foo.paramTypes().get(0));
    }

    @Test
    void stripsParameterAnnotationsFromType() {
        // Mojmaps emits annotations on params, e.g.
        //   updatePowerStrength(Level l, BlockPos p, BlockState s, @Nullable Orientation o, boolean b)
        // The annotation must not leak into the recorded type.
        List<MethodSignature> sigs = JavaSignatureExtractor.extract(
            "package p; public class C {"
                + " public void f(@Nullable Orientation o, @Block.UpdateFlags final int flags) {} }");
        MethodSignature f = find(sigs, "f");
        assertNotNull(f);
        assertEquals(List.of("Orientation", "int"), f.paramTypes(),
            "parameter annotations and 'final' must be stripped from the type");
    }

    // ── Differ classification ─────────────────────────────────────────────────

    private static MethodSignature sig(String name, String ret, List<String> params, String... mods) {
        return new MethodSignature("net.minecraft.Foo", name, ret, params, List.of(mods));
    }

    @Test
    void unchangedMethodIsLevel0() {
        MethodSignature a = sig("tick", "void", List.of("Level", "BlockPos"), "public");
        List<ClassifiedChange> changes = SignatureDiffer.classify(
            List.of(a), List.of(a), List.of(a.identityKey()));
        assertEquals(1, changes.size());
        assertEquals(ChangeLevel.LEVEL_0, changes.get(0).level());
    }

    @Test
    void changedParamTypesIsLevel1() {
        MethodSignature oldS = sig("tick", "void", List.of("Level", "BlockPos"), "public");
        // Same name + arity, different param type (BlockPos → long).
        MethodSignature newS = sig("tick", "void", List.of("Level", "long"), "public");
        List<ClassifiedChange> changes = SignatureDiffer.classify(
            List.of(oldS), List.of(newS), List.of(oldS.identityKey()));
        assertEquals(ChangeLevel.LEVEL_1, changes.get(0).level());
        assertNotNull(changes.get(0).newSig());
    }

    @Test
    void changedReturnTypeIsLevel2() {
        MethodSignature oldS = sig("getDelay", "int", List.of("BlockState"), "protected");
        MethodSignature newS = sig("getDelay", "long", List.of("BlockState"), "protected");
        List<ClassifiedChange> changes = SignatureDiffer.classify(
            List.of(oldS), List.of(newS), List.of(oldS.identityKey()));
        assertEquals(ChangeLevel.LEVEL_2, changes.get(0).level(),
            "a return-type change may alter behaviour → needs re-annotation");
    }

    @Test
    void modifierChangeIsLevel2() {
        MethodSignature oldS = sig("tick", "void", List.of("Level"), "public");
        MethodSignature newS = sig("tick", "void", List.of("Level"), "public", "static");
        List<ClassifiedChange> changes = SignatureDiffer.classify(
            List.of(oldS), List.of(newS), List.of(oldS.identityKey()));
        assertEquals(ChangeLevel.LEVEL_2, changes.get(0).level());
    }

    @Test
    void disappearedMethodIsLevel2() {
        MethodSignature oldS = sig("removed", "void", List.of(), "public");
        List<ClassifiedChange> changes = SignatureDiffer.classify(
            List.of(oldS), List.of(), List.of(oldS.identityKey()));
        assertEquals(ChangeLevel.LEVEL_2, changes.get(0).level());
        org.junit.jupiter.api.Assertions.assertNull(changes.get(0).newSig());
    }

    @Test
    void summaryAndAutoMigrationRate() {
        MethodSignature l0 = sig("a", "void", List.of(), "public");
        MethodSignature l1old = sig("b", "void", List.of("int"), "public");
        MethodSignature l1new = sig("b", "void", List.of("long"), "public");
        MethodSignature l2old = sig("c", "int", List.of(), "public");
        MethodSignature l2new = sig("c", "long", List.of(), "public");

        List<ClassifiedChange> changes = SignatureDiffer.classify(
            List.of(l0, l1old, l2old),
            List.of(l0, l1new, l2new),
            List.of(l0.identityKey(), l1old.identityKey(), l2old.identityKey()));

        Map<ChangeLevel, Integer> counts = SignatureDiffer.summarise(changes);
        assertEquals(1, counts.get(ChangeLevel.LEVEL_0));
        assertEquals(1, counts.get(ChangeLevel.LEVEL_1));
        assertEquals(1, counts.get(ChangeLevel.LEVEL_2));
        // Level 0 + Level 1 auto-migrate → 2/3.
        assertEquals(2.0 / 3.0, SignatureDiffer.autoMigrationRate(changes), 1e-9);
    }

    @Test
    void annotationReferencingMissingOldMethodIsSkipped() {
        MethodSignature present = sig("real", "void", List.of(), "public");
        List<ClassifiedChange> changes = SignatureDiffer.classify(
            List.of(present), List.of(present),
            List.of("net.minecraft.Foo#ghost()"));
        assertTrue(changes.isEmpty(), "an annotation key absent from the old source is skipped");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static boolean hasMethod(List<MethodSignature> sigs, String name) {
        return sigs.stream().anyMatch(s -> s.name().equals(name));
    }

    private static MethodSignature find(List<MethodSignature> sigs, String name) {
        return sigs.stream().filter(s -> s.name().equals(name)).findFirst().orElse(null);
    }
}
