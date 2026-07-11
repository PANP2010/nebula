package org.nebula.plugin;

/**
 * Common shape for stateless-singleton RW-guard tracers.
 *
 * <p>Each concrete {@code XxxRwGuardTracer} is a stateless bridge from a domain-specific
 * {@code *AccessTracer} hook to {@link org.nebula.guard.ThreadLocalAccessTrace}. All state
 * lives in the thread-local trace, so the bridgers are pure singletons. This base just
 * encodes that shared contract so subclasses don't each repeat a private constructor.
 *
 * <p>The per-tracer method bodies (e.g. {@code onFieldRead(EntityField) →
 * ThreadLocalAccessTrace.traceEntityRead(field)}) are not factored further: the five
 * {@code *AccessTracer} interfaces have incompatible shapes ({@code onFieldRead},
 * {@code onBlockRead}, {@code onRandomCall}, … taking {@code WorldPos} /
 * {@code EntityField} / {@code BlockEntityField} / {@code RandomInstance}), and
 * {@code ThreadLocalAccessTrace} exposes nine type-specific {@code traceXxx*} sinks
 * rather than one {@code traceAccess(AccessTarget)} dispatch — so no single
 * {@code onRead(T)} / {@code onWrite(T)} override pair can satisfy all five
 * interfaces. This base is the honest ceiling of what can be cleanly shared.
 */
public abstract class RwGuardTracer {
    protected RwGuardTracer() {}
}