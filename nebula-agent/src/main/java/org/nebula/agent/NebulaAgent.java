package org.nebula.agent;

import java.lang.instrument.Instrumentation;

public final class NebulaAgent {
    private NebulaAgent() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        install(instrumentation);
    }

    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        install(instrumentation);
    }

    private static void install(Instrumentation instrumentation) {
        instrumentation.addTransformer(new NebulaClassFileTransformer(), true);
    }
}
