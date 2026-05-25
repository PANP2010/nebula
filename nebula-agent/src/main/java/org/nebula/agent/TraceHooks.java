package org.nebula.agent;

import org.nebula.core.state.GlobalKey;
import org.nebula.guard.ThreadLocalAccessTrace;

public final class TraceHooks {
    private TraceHooks() {
    }

    public static void traceFieldAccess(String owner, String fieldName, String descriptor, boolean write) {
        GlobalKey key = new GlobalKey("field:" + owner.replace('/', '.') + "#" + fieldName + ":" + descriptor);
        if (write) {
            ThreadLocalAccessTrace.traceGlobalWrite(key);
        } else {
            ThreadLocalAccessTrace.traceGlobalRead(key);
        }
    }
}
