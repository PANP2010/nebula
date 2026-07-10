package org.nebula.plugin;

import org.nebula.core.state.EntityField;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.EntityAccessTracer;
import org.nebula.guard.ThreadLocalAccessTrace;

/** Bridges live entity-context accesses into the RW-guard trace (B8 C2). */
public final class EntityRwGuardTracer implements EntityAccessTracer {

    public static final EntityRwGuardTracer INSTANCE = new EntityRwGuardTracer();

    private EntityRwGuardTracer() {
    }

    @Override
    public void onFieldRead(EntityField field) {
        ThreadLocalAccessTrace.traceEntityRead(field);
    }

    @Override
    public void onFieldWrite(EntityField field) {
        ThreadLocalAccessTrace.traceEntityWrite(field);
    }

    @Override
    public void onBlockRead(WorldPos pos) {
        ThreadLocalAccessTrace.traceBlockRead(pos);
    }
}
