package org.nebula.maintenance.asm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanity tests for {@link NebulaRWPatchGenerator}'s core parser logic.
 *
 * <p>Two regressions are pinned here:
 * <ul>
 *   <li>Method names without {@code (params)} must still parse (the inferrer's
 *       output emits just the bare name with no signature).</li>
 *   <li>Multi-line annotation bodies must produce one {@code +} prefix per line
 *       so the resulting patch is diff-applyable.</li>
 * </ul>
 */
class NebulaRWPatchGeneratorTest {

    /** Minimal input mimicking {@code NebulaRWSourceInferrer}'s output format. */
    private static String sampleInferenceOutput() {
        return ""
            + "// Auto-generated @NebulaRW annotations\n"
            + "// Generated: test\n"
            + "\n"
            + "// Confidence: 0.65\n"
            + "// Method: net.minecraft.world.entity.Entity.java#tick\n"
            + "@org.nebula.annotations.NebulaRW(\n"
            + "    readEntities = {\"this.*\"},\n"
            + "    maxRandomCalls = 4,\n"
            + "    verifiedAt = \"1.21.4\"\n"
            + ")\n"
            + "\n";
    }

    @Test
    void methodNameWithoutParensIsAccepted() {
        // The regressed parsing path was: methodSig.indexOf('(') == -1 → continue.
        // A correct render must include the bare method name "tick" in the patch.
        String out = NebulaRWPatchGenerator.renderPatchText(
            sampleInferenceOutput(), "test-classes", 9999);
        assertTrue(out.contains("tick"),
            "expected 'tick' in rendered patch, was: " + out);
        // Diff-applyability: the patch header must use the Paper
        // "diff --git a/PATH b/PATH" convention.
        assertTrue(out.contains("diff --git a/net.minecraft.world.entity.Entity.java"),
            "expected proper diff --git line, was: " + out.split("\n")[3]);
    }

    @Test
    void multiLineBodyEachLineHasPlusPrefix() {
        String out = NebulaRWPatchGenerator.renderPatchText(
            sampleInferenceOutput(), "test-classes", 9999);
        boolean sawReadLine = false;
        boolean sawMaxRandom = false;
        for (String l : out.split("\n")) {
            if (l.startsWith("+readEntities")) sawReadLine = true;
            if (l.startsWith("+maxRandomCalls")) sawMaxRandom = true;
        }
        assertTrue(sawReadLine, "expected '+readEntities ...' prefixed line in: " + out);
        assertTrue(sawMaxRandom, "expected '+maxRandomCalls ...' prefixed line in: " + out);
    }
}
