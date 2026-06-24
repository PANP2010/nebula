package org.nebula.agent;

import java.lang.instrument.Instrumentation;

public final class NebulaAgent {

    private static volatile Instrumentation globalInstrumentation = null;

    private NebulaAgent() {}

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        install(instrumentation);
    }

    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        install(instrumentation);
    }

    private static void install(Instrumentation instrumentation) {
        globalInstrumentation = instrumentation;
        instrumentation.addTransformer(new NebulaClassFileTransformer(), true);
        
        // Set the sentinel flag immediately so NebulaPlugin can verify hooks are active.
        // Previously this was set in each instrumented method's visitCode(), but that
        // only happened when the method was called, causing a timing issue during plugin startup.
        NeighborUpdateHooks.hooksActive = true;
        System.err.println("[NebulaAgent] hooksActive sentinel set to true");
    }

    /** Returns the global Instrumentation instance for retransform. */
    public static Instrumentation getInstrumentation() {
        return globalInstrumentation;
    }
}
