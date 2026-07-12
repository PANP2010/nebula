package org.nebula.maintenance.asm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Generates candidate {@code @NebulaRW} annotation patches for methods that
 * the {@link MethodSignatureDeltaDetector} flagged as
 * {@link DiffLevel#NEW} (L0, pure addition).
 *
 * <p>This is the "human-review workflow" entry point called out in the
 * Phase 1.5 spec:
 * <ol>
 *   <li>{@link MethodSignatureDeltaDetector} runs on the old vs new class
 *       files and produces {@link MethodDelta}s.</li>
 *   <li>For each L0 delta, this patcher drafts a {@link PatchCandidate}
 *       carrying a conservative {@code @NebulaRW} suggestion.</li>
 *   <li>A human reviews the patch, tweaks the read/write-set based on the
 *       method's actual behaviour, then either confirms or edits the patch
 *       into the production annotation library.</li>
 * </ol>
 *
 * <h2>What the draft looks like</h2>
 * The patcher does NOT attempt to infer read/write sets from bytecode data
 * flow — that's a deeper analysis and is out of scope for the initial MSD.
 * Instead it produces a <em>conservative minimal draft</em> with empty
 * read/write arrays and explicit placeholders for the human reviewer:
 *
 * <pre>{@code
 * @NebulaRW(
 *     readBlocks  = {"<TODO: confirm>"},
 *     writeBlocks = {"<TODO: confirm>"},
 *     triggeredEvents = {"<TODO>"},
 *     verifiedAt = "<TODO>",
 *     verifiedBy = {"<TODO>"}
 * )
 * public void customName(String name) { ... }
 * }</pre>
 *
 * The reviewer is forced to look at the method body and fill in the
 * placeholders. This is intentional: a false-positive annotation is worse
 * than no annotation, and a smart-looking-but-wrong guess would erode trust
 * in the maintenance toolchain.
 *
 * <h2>Why placeholders, not stubs</h2>
 * Stubs ({@code readBlocks = {}}) would silently pass the {@code @NebulaRW}
 * validator while carrying zero semantic information — a "silent failure"
 * pattern the project specifically tries to avoid (see
 * {@code AnnotationRegressionRunner}). The placeholder string {@code "<TODO: confirm>"}
 * is detected by the regression runner as "needs human action" and forces
 * the patch through review before it can be merged.
 */
public final class NebulaRWAnnotationPatcher {

    /**
     * Methods whose name matches one of these patterns are skipped by the
     * patcher. These are obviously-internal accessors that don't need a
     * NebulaRW annotation (they're never a Minecraft tick hot path).
     */
    private static final Set<String> SKIP_NAME_PREFIXES = Set.of(
        "get", "set", "is", "has", "can", "should", "lambda$"
    );

    private boolean skipAccessors = true;

    /** If true (default), obvious accessors are skipped from auto-draft. */
    public NebulaRWAnnotationPatcher setSkipAccessors(boolean value) {
        this.skipAccessors = value;
        return this;
    }

    /**
     * Generate a {@link PatchCandidate} for a single {@link MethodDelta}.
     * Returns {@code null} if the delta's level is not auto-draftable, or if
     * the method matches a skip rule.
     */
    public PatchCandidate draft(MethodDelta delta) {
        Objects.requireNonNull(delta, "delta");
        if (!delta.level().isAutoDraftable()) return null;
        BytecodeMethodDescriptor newDesc = delta.newDescOrNull();
        if (newDesc == null) return null;

        if (skipAccessors && isAccessor(newDesc.methodName())) {
            return null;
        }

        String annotation = renderAnnotation(newDesc);
        return new PatchCandidate(
            delta.ownerClass(),
            delta.methodName(),
            delta.newJvmDescriptor(),
            delta.level(),
            annotation,
            PatchCandidate.Note.HUMAN_REVIEW_REQUIRED,
            buildReasoning(delta));
    }

    /**
     * Batch-generate patch candidates for a list of deltas. Returns one
     * {@link PatchCandidate} per auto-draftable delta (skipping non-draftable
     * ones and methods matched by skip rules).
     */
    public List<PatchCandidate> draftAll(List<MethodDelta> deltas) {
        Objects.requireNonNull(deltas, "deltas");
        List<PatchCandidate> out = new ArrayList<>();
        for (MethodDelta d : deltas) {
            PatchCandidate c = draft(d);
            if (c != null) out.add(c);
        }
        return out;
    }

    /** True iff the method name starts with one of the configured skip prefixes. */
    static boolean isAccessor(String name) {
        for (String p : SKIP_NAME_PREFIXES) {
            if (name.startsWith(p)) return true;
        }
        return false;
    }

    private static String renderAnnotation(BytecodeMethodDescriptor desc) {
        StringBuilder sb = new StringBuilder();
        sb.append("@NebulaRW(\n");
        sb.append("    readBlocks   = {\"<TODO: confirm>\"},\n");
        sb.append("    writeBlocks  = {\"<TODO: confirm>\"},\n");
        sb.append("    readInternalState  = {\"<TODO: confirm>\"},\n");
        sb.append("    writeInternalState = {\"<TODO: confirm>\"},\n");
        sb.append("    triggeredEvents    = {\"<TODO: confirm>\"},\n");
        sb.append("    microStep  = MicroStepBehavior.NONE,\n");
        sb.append("    scc        = SccBehavior.AUTO,\n");
        sb.append("    mayLoadChunks       = false,\n");
        sb.append("    mayTriggerBlockUpdates = false,\n");
        sb.append("    maySpawnEntities    = false,\n");
        sb.append("    verifiedAt = \"<TODO: fill Minecraft version>\",\n");
        sb.append("    verifiedBy = {\"<TODO: fill test method name>\"}\n");
        sb.append(")");
        sb.append("  // ").append(desc.visibilityLabel())
          .append(desc.isStatic() ? " static" : "")
          .append(" ").append(desc.methodName()).append(desc.descriptor());
        return sb.toString();
    }

    private static String buildReasoning(MethodDelta delta) {
        return switch (delta.level()) {
            case NEW -> "L0 pure addition — no prior annotation exists; placeholder draft.";
            case DESCRIPTOR_CHANGED -> "L1 descriptor change — descriptor was "
                + delta.oldJvmDescriptor() + "; reviewer must confirm read/write-set still applies.";
            default -> "Unexpected level " + delta.level() + " for draft — escalate.";
        };
    }

    /**
     * A candidate {@code @NebulaRW} annotation patch generated from an MSD delta.
     *
     * @param targetClass   internal class name the patch targets
     * @param targetMethod  method name
     * @param targetDescriptor the post-update JVM descriptor
     * @param level         the {@link DiffLevel} that produced this candidate
     * @param annotation    the rendered {@code @NebulaRW(...)} text
     * @param note          action the reviewer must take
     * @param reasoning     why this patch was drafted
     */
    public record PatchCandidate(
        String targetClass,
        String targetMethod,
        String targetDescriptor,
        DiffLevel level,
        String annotation,
        Note note,
        String reasoning
    ) {
        public PatchCandidate {
            Objects.requireNonNull(targetClass, "targetClass");
            Objects.requireNonNull(targetMethod, "targetMethod");
            Objects.requireNonNull(targetDescriptor, "targetDescriptor");
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(annotation, "annotation");
            Objects.requireNonNull(note, "note");
            reasoning = reasoning == null ? "" : reasoning;
        }

        public enum Note {
            /** The draft is a placeholder; the human must fill in the TODO fields. */
            HUMAN_REVIEW_REQUIRED,
            /** The patch was rejected by the patcher's own filters. */
            SKIPPED
        }
    }
}