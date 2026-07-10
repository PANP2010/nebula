package org.nebula.entity;

import org.nebula.core.state.EntityField;
import org.nebula.core.state.WorldPos;

/**
 * Optional hook that observes every entity-field and terrain-block access performed
 * through an {@link EntityTaskContext}. The entity analogue of
 * {@link BlockEntityAccessTracer}.
 *
 * <p>The live RW-guard implementation lives in {@code nebula-plugin}, which bridges
 * these callbacks into its thread-local access trace. When no tracer is installed,
 * entity actions run without any tracing overhead.
 */
public interface EntityAccessTracer {

    void onFieldRead(EntityField field);

    void onFieldWrite(EntityField field);

    void onBlockRead(WorldPos pos);
}
