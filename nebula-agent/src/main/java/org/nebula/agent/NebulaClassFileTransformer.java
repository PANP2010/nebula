package org.nebula.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public final class NebulaClassFileTransformer implements ClassFileTransformer {
    @Override
    public byte[] transform(
        ClassLoader loader,
        String className,
        Class<?> classBeingRedefined,
        ProtectionDomain protectionDomain,
        byte[] classfileBuffer
    ) {
        if (className == null) return null;

        // 1. CollectingNeighborUpdater hook — queued updates (repeaters, comparators, etc.)
        if (NeighborUpdateTransformer.TARGET_CLASS.equals(className)) {
            System.err.println("[Nebula] Instrumenting CollectingNeighborUpdater via agent");
            byte[] result = NeighborUpdateTransformer.maybeInstrument(className, classfileBuffer);
            System.err.println("[Nebula] CollectingNeighborUpdater instrumentation complete");
            return result;
        }

        // 2. InstantNeighborUpdater hook — direct updates (setblock, pistons, etc.)
        if (InstantNeighborUpdateTransformer.TARGET_CLASS.equals(className)) {
            System.err.println("[Nebula] Instrumenting InstantNeighborUpdater via agent");
            byte[] result = InstantNeighborUpdateTransformer.maybeInstrument(className, classfileBuffer);
            System.err.println("[Nebula] InstantNeighborUpdater instrumentation complete");
            return result;
        }

        // 2b. NeighborUpdater.executeUpdate static method — Folia 26.1.2 routing
        if (ExecuteUpdateTransformer.TARGET_CLASS.equals(className)) {
            System.err.println("[Nebula] Instrumenting NeighborUpdater.executeUpdate via agent");
            byte[] result = ExecuteUpdateTransformer.maybeInstrument(className, classfileBuffer);
            System.err.println("[Nebula] NeighborUpdater.executeUpdate instrumentation complete");
            return result;
        }

        // 2c. RedstoneWireTurbo — Folia's optimized redstone wire update path
        if (RedstoneWireTurboTransformer.TARGET_CLASS.equals(className)) {
            System.err.println("[Nebula] Instrumenting RedstoneWireTurbo via agent");
            byte[] result = RedstoneWireTurboTransformer.maybeInstrument(className, classfileBuffer);
            System.err.println("[Nebula] RedstoneWireTurbo instrumentation complete");
            return result;
        }
        // Debug: log NMS class names seen
        if (className != null && className.startsWith("net/minecraft/world/level/redstone/")) {
            System.err.println("[Nebula/Debug] Saw class: " + className);
        }

        // 3. VAP Level 0 API interception — rewrites plugin→Bukkit calls
        if (Boolean.getBoolean("nebula.vap.intercept")
                && VapApiInterceptTransformer.shouldTransform(className)) {
            return VapApiInterceptTransformer.instrument(className, classfileBuffer);
        }

        // 4. Full RW-set access tracing — only when nebula.rw.guard=true
        if (shouldSkipRwTrace(className)) return null;
        if (!Boolean.getBoolean("nebula.rw.guard")) return null;
        return AccessTracingTransformer.instrument(className, classfileBuffer);
    }

    private static boolean shouldSkipRwTrace(String className) {
        return className.startsWith("java/")
            || className.startsWith("javax/")
            || className.startsWith("jdk/")
            || className.startsWith("sun/")
            || className.startsWith("com/sun/")
            || className.startsWith("org/gradle/")
            || className.startsWith("org/junit/")
            || className.startsWith("org/objectweb/asm/")
            || className.startsWith("org/nebula/guard/")
            || className.startsWith("org/nebula/agent/");
    }
}
