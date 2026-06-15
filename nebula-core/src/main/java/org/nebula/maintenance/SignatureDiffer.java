package org.nebula.maintenance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Diffs two versions' method signatures and classifies each change by
 * {@link ChangeLevel} (NEBULA-PATCH-2026-001 §变更一, §14.3.5 组件 A).
 *
 * <p>Classification heuristic (source-signature level; the production tool also
 * uses bytecode data-flow summaries for Level 2, which is out of scope here):
 * <ul>
 *   <li><b>Level 0</b> — a method with the same identity key (owner+name+param
 *       types) and unchanged return type and modifier set. No impact; the old
 *       annotation migrates automatically.</li>
 *   <li><b>Level 1</b> — a method matched by owner+name+arity but with changed
 *       parameter <em>types</em> or order, or matched by owner+arity+types under
 *       a new name (rename). A new annotation draft can be generated for
 *       confirmation.</li>
 *   <li><b>Level 2</b> — anything else affecting an existing annotated method:
 *       a return-type change, a modifier change (e.g. gaining/losing static), or
 *       a method that disappeared with no rename match. These need re-annotation
 *       because the read/write-set behaviour may have changed.</li>
 * </ul>
 *
 * <p>Only methods present in the {@code annotated} set are classified — the
 * maintenance toolchain only cares about methods that carry {@code @NebulaRW}
 * annotations.
 */
public final class SignatureDiffer {

    /** One classified change for an annotated method. */
    public record ClassifiedChange(MethodSignature oldSig, MethodSignature newSig, ChangeLevel level) {}

    private SignatureDiffer() {}

    /**
     * Classifies how each annotated method changed between {@code oldVersion}
     * and {@code newVersion}.
     *
     * @param oldVersion  signatures from the old decompiled source
     * @param newVersion  signatures from the new decompiled source
     * @param annotatedKeys identity keys of methods that carry annotations
     * @return one {@link ClassifiedChange} per annotated method (in input order)
     */
    public static List<ClassifiedChange> classify(
        List<MethodSignature> oldVersion,
        List<MethodSignature> newVersion,
        List<String> annotatedKeys
    ) {
        Map<String, MethodSignature> oldById = byIdentity(oldVersion);
        Map<String, MethodSignature> newById = byIdentity(newVersion);
        Map<String, MethodSignature> newByNameArity = byNameArity(newVersion);

        List<ClassifiedChange> changes = new ArrayList<>();
        for (String key : annotatedKeys) {
            MethodSignature oldSig = oldById.get(key);
            if (oldSig == null) {
                continue; // annotation references a method not in the old source — skip
            }

            MethodSignature exact = newById.get(key);
            if (exact != null) {
                // Same identity. Level 0 unless return type or modifiers changed.
                boolean sameReturn = exact.returnType().equals(oldSig.returnType());
                boolean sameMods = exact.modifiers().equals(oldSig.modifiers());
                ChangeLevel level = (sameReturn && sameMods) ? ChangeLevel.LEVEL_0 : ChangeLevel.LEVEL_2;
                changes.add(new ClassifiedChange(oldSig, exact, level));
                continue;
            }

            // No exact match. A same-name+arity method with different param types
            // is a signature change (Level 1, auto-draftable).
            MethodSignature byNameArity = newByNameArity.get(oldSig.nameArityKey());
            if (byNameArity != null) {
                changes.add(new ClassifiedChange(oldSig, byNameArity, ChangeLevel.LEVEL_1));
                continue;
            }

            // Disappeared with no signature-compatible match → needs re-annotation.
            changes.add(new ClassifiedChange(oldSig, null, ChangeLevel.LEVEL_2));
        }
        return changes;
    }

    /** Summary counts per change level, for the coverage dashboard / CI report. */
    public static Map<ChangeLevel, Integer> summarise(List<ClassifiedChange> changes) {
        Map<ChangeLevel, Integer> counts = new LinkedHashMap<>();
        for (ChangeLevel l : ChangeLevel.values()) counts.put(l, 0);
        for (ClassifiedChange c : changes) counts.merge(c.level(), 1, Integer::sum);
        return counts;
    }

    /**
     * Fraction of changes that migrate automatically (Level 0 + Level 1).
     * The patch's expected coverage is 60–80%.
     */
    public static double autoMigrationRate(List<ClassifiedChange> changes) {
        if (changes.isEmpty()) return 1.0;
        long auto = changes.stream().filter(c -> c.level().isAutomatable()).count();
        return (double) auto / changes.size();
    }

    private static Map<String, MethodSignature> byIdentity(List<MethodSignature> sigs) {
        Map<String, MethodSignature> m = new LinkedHashMap<>();
        for (MethodSignature s : sigs) m.put(s.identityKey(), s);
        return m;
    }

    private static Map<String, MethodSignature> byNameArity(List<MethodSignature> sigs) {
        Map<String, MethodSignature> m = new LinkedHashMap<>();
        for (MethodSignature s : sigs) m.putIfAbsent(s.nameArityKey(), s);
        return m;
    }
}
