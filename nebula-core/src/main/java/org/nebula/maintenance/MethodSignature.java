package org.nebula.maintenance;

import java.util.List;
import java.util.Objects;

/**
 * A parsed method signature from a decompiled Minecraft source
 * (NEBULA-PATCH-2026-001 §变更一, §14.3.5 组件 A).
 *
 * <p>The Method Signature change Detector (MSD) extracts these from old and new
 * decompiled sources and diffs them to classify how each method changed. A
 * signature is identified by its owning class plus name plus parameter types;
 * the return type and modifiers are compared but not part of identity (a
 * return-type-only change is still "the same method, changed").
 *
 * @param ownerClass  fully-qualified declaring class (e.g.
 *                    {@code net.minecraft.world.level.block.RedStoneWireBlock})
 * @param name        method name
 * @param returnType  return type as written in source (e.g. {@code BlockState})
 * @param paramTypes  parameter types in declaration order
 * @param modifiers   modifier keywords present (public/protected/private/static/final)
 */
public record MethodSignature(
    String ownerClass,
    String name,
    String returnType,
    List<String> paramTypes,
    List<String> modifiers
) {
    public MethodSignature {
        Objects.requireNonNull(ownerClass, "ownerClass");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(returnType, "returnType");
        paramTypes = List.copyOf(paramTypes);
        modifiers = List.copyOf(modifiers);
    }

    /**
     * Identity key: owner + name + parameter types. Two signatures with the same
     * key are "the same method" for diff purposes; differences in return type or
     * modifiers are changes <em>to</em> that method, not a different method.
     */
    public String identityKey() {
        return ownerClass + "#" + name + "(" + String.join(",", paramTypes) + ")";
    }

    /** Key ignoring parameter <em>types</em> but keeping arity — used to detect renames/reorders. */
    public String nameArityKey() {
        return ownerClass + "#" + name + "/" + paramTypes.size();
    }

    public boolean isStatic() {
        return modifiers.contains("static");
    }
}
