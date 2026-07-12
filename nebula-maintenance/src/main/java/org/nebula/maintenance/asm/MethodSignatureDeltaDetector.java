package org.nebula.maintenance.asm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Method Signature Delta detector (MSD) — Phase 1.5, NEBULA-PATCH-2026-001
 * §14.3.5 组件 A.
 *
 * <p>This is the bytecode-level diff. It compares two sets of
 * {@link BytecodeMethodDescriptor}s (the old MCP/NMS class files vs the new
 * ones) and produces one {@link MethodDelta} per detected change. The
 * classifier is the same L0/L1/L2 scheme as the source-side
 * {@code org.nebula.maintenance.SignatureDiffer}, but the rules are stricter
 * because bytecode exposes information that source does not:
 *
 * <ul>
 *   <li><b>NEW (L0)</b> — descriptor present in {@code newMethods} but not in
 *       {@code oldMethods} at the same identity key (owner#name descriptor).
 *       The simplest signal: a method Mojang added in the new release.</li>
 *   <li><b>DESCRIPTOR_CHANGED (L1)</b> — same owner#name but different
 *       descriptor in the new version (parameter types changed or return type
 *       changed). The {@code @NebulaRW} on the old method may have stale
 *       read/write-set; the patcher can draft a replacement.</li>
 *   <li><b>VISIBILITY_CHANGED (L2)</b> — same owner#name+descriptor but the
 *       access flags changed. Examples:
 *       <ul>
 *         <li>{@code public → protected/private} — the method's external
 *             contract changed; subclass overrides and plugin callers break.</li>
 *         <li>{@code package → public} — visibility widening; previously
 *             invisible callers may now bind.</li>
 *         <li>adding/removing {@code final} — affects overriding.</li>
 *         <li>adding/removing {@code static} — drastic binding change.</li>
 *       </ul>
 *       These need human review because the annotation's runtime assumptions
 *       (about who calls the method and via which dispatcher) may be wrong.</li>
 *   <li><b>REMOVED (L2)</b> — descriptor present in old but absent from new.
 *       The old {@code @NebulaRW} is orphaned. Human review needed: either
 *       delete the annotation or rebind it to the new method (if the rename
 *       was caught by {@link #setRenameHint(String, String)}).</li>
 * </ul>
 *
 * <h2>Rename hints</h2>
 * If the caller knows that {@code old#foo(I)V} became {@code new#bar(I)V}
 * (a method rename), set a rename hint before calling {@link #detect}. The
 * detector will treat the rename pair as a single {@link DiffLevel#DESCRIPTOR_CHANGED}
 * delta (since the descriptor stays the same) and suppress the standalone
 * NEW/REMOVED entries. This is the bridge between the bytecode diff and the
 * human-review workflow: a reviewer marks the rename on the source side, the
 * detector absorbs it.
 *
 * <h2>Expected automation coverage</h2>
 * Per the patch: {@code NEW} (40-50%) + {@code DESCRIPTOR_CHANGED} (20-30%) =
 * 60-80% auto-migrate. {@code VISIBILITY_CHANGED} and {@code REMOVED} always
 * require human review.
 */
public final class MethodSignatureDeltaDetector {

    /**
     * Optional override of the default visibility bitmask. Used by tests to
     * narrow "what counts as a visibility change" (e.g. treat static/final
     * toggles as not visibility changes). Defaults to the standard
     * public/protected/private/package mask.
     */
    public static final int DEFAULT_VISIBILITY_MASK =
        java.lang.reflect.Modifier.PUBLIC
            | java.lang.reflect.Modifier.PROTECTED
            | java.lang.reflect.Modifier.PRIVATE;

    private final Set<RenamePair> renameHints = new LinkedHashSet<>();
    private int visibilityMask = DEFAULT_VISIBILITY_MASK;
    private boolean treatStaticFinalAsVisibility = false;

    /**
     * Register a rename hint: the method {@code oldRef} is the pre-update
     * identity key of a method that was renamed to {@code newRef}. After
     * detection, that pair is collapsed into a single DESCRIPTOR_CHANGED delta
     * if both sides agree on the descriptor; otherwise it remains
     * REMOVED + NEW.
     */
    public MethodSignatureDeltaDetector setRenameHint(String oldRef, String newRef) {
        Objects.requireNonNull(oldRef, "oldRef");
        Objects.requireNonNull(newRef, "newRef");
        renameHints.add(new RenamePair(oldRef, newRef));
        return this;
    }

    /** Narrow the visibility bitmask. By default all four visibilities are watched. */
    public MethodSignatureDeltaDetector setVisibilityMask(int mask) {
        this.visibilityMask = mask;
        return this;
    }

    /**
     * If true, toggles of {@code static} or {@code final} are also classified
     * as VISIBILITY_CHANGED. Off by default because those are usually source-
     * level refactors that don't change call dispatch.
     */
    public MethodSignatureDeltaDetector setTreatStaticFinalAsVisibility(boolean value) {
        this.treatStaticFinalAsVisibility = value;
        return this;
    }

    /**
     * Run the diff between two collections of method descriptors.
     *
     * @param oldMethods methods extracted from the old version's class files
     * @param newMethods methods extracted from the new version's class files
     * @return ordered list of {@link MethodDelta}s, one per detected change
     */
    public List<MethodDelta> detect(Collection<BytecodeMethodDescriptor> oldMethods,
                                   Collection<BytecodeMethodDescriptor> newMethods) {
        Objects.requireNonNull(oldMethods, "oldMethods");
        Objects.requireNonNull(newMethods, "newMethods");

        Map<String, BytecodeMethodDescriptor> oldByKey = indexByKey(oldMethods);
        Map<String, BytecodeMethodDescriptor> newByKey = indexByKey(newMethods);

        // Set of old identity keys that are consumed as rename sources. These
        // are removed from oldByKey so they are NOT reported as REMOVED.
        Set<String> consumedOldKeys = new LinkedHashSet<>();

        // Apply rename hints: remove the old-key entry from oldByKey so it is
        // not reported as REMOVED, and remember the pair so we can emit a
        // DESCRIPTOR_CHANGED delta (the descriptor IS the delta the patcher
        // must handle; the name change is just context).
        Map<String, BytecodeMethodDescriptor> renamedOldDescriptors = new LinkedHashMap<>();
        for (RenamePair hint : renameHints) {
            BytecodeMethodDescriptor old = oldByKey.remove(hint.oldRef());
            if (old != null) {
                renamedOldDescriptors.put(hint.newRef(), old);
                consumedOldKeys.add(hint.oldRef());
            }
        }

        List<MethodDelta> deltas = new ArrayList<>();

        // Walk NEW: each entry is either matched, renamed, same-owner-samename,
        // or brand new.
        for (Map.Entry<String, BytecodeMethodDescriptor> e : newByKey.entrySet()) {
            String newKey = e.getKey();
            BytecodeMethodDescriptor newDesc = e.getValue();

            BytecodeMethodDescriptor oldDesc = oldByKey.get(newKey);
            if (oldDesc == null) {
                // Not at the exact identity key. Could be a rename source,
                // a same-owner+same-name with different descriptor (L1), or
                // a pure addition (L0).
                BytecodeMethodDescriptor fromRename = renamedOldDescriptors.get(newKey);
                if (fromRename != null) {
                    // Emit one DESCRIPTOR_CHANGED delta (rename collapses the
                    // REMOVED+NEW split into a single actionable delta).
                    deltas.add(new MethodDelta(
                        newDesc.ownerClass(), newDesc.methodName(),
                        java.util.Optional.of(fromRename),
                        java.util.Optional.of(newDesc),
                        DiffLevel.DESCRIPTOR_CHANGED,
                        "was " + fromRename.descriptor()));
                    consumedOldKeys.add(newKey);
                    continue;
                }
                // Same-owner+same-name but different descriptor → L1.
                BytecodeMethodDescriptor sameName =
                    findByOwnerName(oldByKey, newDesc.ownerClass(), newDesc.methodName());
                if (sameName != null) {
                    deltas.add(new MethodDelta(
                        newDesc.ownerClass(), newDesc.methodName(),
                        java.util.Optional.of(sameName),
                        java.util.Optional.of(newDesc),
                        DiffLevel.DESCRIPTOR_CHANGED,
                        "was " + sameName.descriptor()));
                    consumedOldKeys.add(sameName.identityKey());
                    continue;
                }
                // Pure addition (L0).
                deltas.add(new MethodDelta(
                    newDesc.ownerClass(), newDesc.methodName(),
                    java.util.Optional.empty(),
                    java.util.Optional.of(newDesc),
                    DiffLevel.NEW,
                    ""));
                continue;
            }

            // Exact match on owner+name+descriptor.
            consumedOldKeys.add(newKey);
            if (visibilityChanged(oldDesc, newDesc)) {
                deltas.add(new MethodDelta(
                    newDesc.ownerClass(), newDesc.methodName(),
                    java.util.Optional.of(oldDesc),
                    java.util.Optional.of(newDesc),
                    DiffLevel.VISIBILITY_CHANGED,
                    "was " + oldDesc.visibilityLabel() + describeFlagsDiff(oldDesc, newDesc)));
            }
            // Identical → no delta.
        }

        // Walk OLD: anything not consumed is REMOVED.
        for (Map.Entry<String, BytecodeMethodDescriptor> e : oldByKey.entrySet()) {
            String oldKey = e.getKey();
            if (consumedOldKeys.contains(oldKey)) continue;
            BytecodeMethodDescriptor oldDesc = e.getValue();
            deltas.add(new MethodDelta(
                oldDesc.ownerClass(), oldDesc.methodName(),
                java.util.Optional.of(oldDesc),
                java.util.Optional.empty(),
                DiffLevel.REMOVED,
                ""));
        }

        return deltas;
    }

    /**
     * Bulk form: index every owner class's methods and run {@link #detect}
     * per class. This is what the report writer uses — it buckets deltas by
     * class so the console output stays readable.
     *
     * @param oldMethodsByClass  map from internal class name to its method list
     * @param newMethodsByClass  same for the new version
     * @return list of {@link PerClassResult}, one per class that had any delta
     */
    public List<PerClassResult> detectByClass(
        Map<String, List<BytecodeMethodDescriptor>> oldMethodsByClass,
        Map<String, List<BytecodeMethodDescriptor>> newMethodsByClass) {

        Objects.requireNonNull(oldMethodsByClass, "oldMethodsByClass");
        Objects.requireNonNull(newMethodsByClass, "newMethodsByClass");

        Map<String, List<BytecodeMethodDescriptor>> empty = new LinkedHashMap<>();
        Set<String> classes = new LinkedHashSet<>();
        classes.addAll(oldMethodsByClass.keySet());
        classes.addAll(newMethodsByClass.keySet());

        List<PerClassResult> results = new ArrayList<>();
        for (String owner : classes) {
            List<BytecodeMethodDescriptor> olds = oldMethodsByClass.getOrDefault(owner, List.of());
            List<BytecodeMethodDescriptor> news = newMethodsByClass.getOrDefault(owner, List.of());
            List<MethodDelta> deltas = detect(olds, news);
            if (!deltas.isEmpty()) {
                results.add(new PerClassResult(owner, deltas));
            }
        }
        return results;
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static Map<String, BytecodeMethodDescriptor> indexByKey(
        Collection<BytecodeMethodDescriptor> methods) {
        Map<String, BytecodeMethodDescriptor> m = new LinkedHashMap<>();
        for (BytecodeMethodDescriptor d : methods) {
            // If multiple methods collide on identity (shouldn't happen for a
            // valid class file), keep the first. The second is dropped silently
            // so a corrupt input doesn't crash the diff.
            m.putIfAbsent(d.identityKey(), d);
        }
        return m;
    }

    private static BytecodeMethodDescriptor findByOwnerName(
        Map<String, BytecodeMethodDescriptor> byKey, String ownerClass, String methodName) {
        for (BytecodeMethodDescriptor d : byKey.values()) {
            if (d.ownerClass().equals(ownerClass) && d.methodName().equals(methodName)) {
                return d;
            }
        }
        return null;
    }

    private boolean visibilityChanged(BytecodeMethodDescriptor oldD, BytecodeMethodDescriptor newD) {
        int oldVis = oldD.access() & visibilityMask;
        int newVis = newD.access() & visibilityMask;
        if (oldVis != newVis) return true;
        if (treatStaticFinalAsVisibility) {
            boolean oldStatic = oldD.isStatic();
            boolean newStatic = newD.isStatic();
            boolean oldFinal = oldD.isFinal();
            boolean newFinal = newD.isFinal();
            if (oldStatic != newStatic || oldFinal != newFinal) return true;
        }
        return false;
    }

    private static String describeFlagsDiff(BytecodeMethodDescriptor oldD, BytecodeMethodDescriptor newD) {
        List<String> diffs = new ArrayList<>();
        if (oldD.isStatic() != newD.isStatic()) {
            diffs.add("static:" + oldD.isStatic() + "->" + newD.isStatic());
        }
        if (oldD.isFinal() != newD.isFinal()) {
            diffs.add("final:" + oldD.isFinal() + "->" + newD.isFinal());
        }
        return diffs.isEmpty() ? "" : " (" + String.join(",", diffs) + ")";
    }

    /** Pair of (old identity key, new identity key) representing a known rename. */
    private record RenamePair(String oldRef, String newRef) {}

    /** All deltas for a single owning class, plus the class identifier for the report. */
    public record PerClassResult(String ownerClass, List<MethodDelta> deltas) {
        public PerClassResult {
            Objects.requireNonNull(ownerClass, "ownerClass");
            deltas = List.copyOf(deltas);
        }

        public int countOf(DiffLevel level) {
            int n = 0;
            for (MethodDelta d : deltas) if (d.level() == level) n++;
            return n;
        }

        public boolean hasAutoDraftable() {
            return deltas.stream().anyMatch(d -> d.level().isAutoDraftable());
        }
    }
}