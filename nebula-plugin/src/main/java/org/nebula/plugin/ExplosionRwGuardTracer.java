package org.nebula.plugin;

import org.nebula.core.state.EntityField;
import org.nebula.core.state.RandomInstance;
import org.nebula.core.state.WorldPos;
import org.nebula.entity.ExplosionAccessTracer;
import org.nebula.guard.ThreadLocalAccessTrace;

/** Bridges pure explosion accesses into the RW guard trace (B8 C4). */
public final class ExplosionRwGuardTracer implements ExplosionAccessTracer {

    public static final ExplosionRwGuardTracer INSTANCE = new ExplosionRwGuardTracer();

    private ExplosionRwGuardTracer() {}

    @Override
    public void onBlockRead(WorldPos pos) {
        ThreadLocalAccessTrace.traceBlockRead(pos);
    }

    @Override
    public void onBlockWrite(WorldPos pos) {
        ThreadLocalAccessTrace.traceBlockWrite(pos);
    }

    @Override
    public void onEntityRead(EntityField field) {
        ThreadLocalAccessTrace.traceEntityRead(field);
    }

    @Override
    public void onEntityWrite(EntityField field) {
        ThreadLocalAccessTrace.traceEntityWrite(field);
    }

    @Override
    public void onRandomCall(RandomInstance instance) {
        ThreadLocalAccessTrace.traceRandomCall(instance);
    }
}
