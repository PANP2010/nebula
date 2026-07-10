package org.nebula.folia;

/**
 * Detects whether the current runtime is Folia (region-threaded) vs vanilla
 * Paper, by probing for a Folia-only class on the classpath.
 *
 * <p>The marker is {@link #REGION_SCHEDULER_CLASS}
 * ({@code RegionScheduler}), which lives in the Folia <em>API</em> and is absent
 * from vanilla Paper — so detection works both when compiling/testing against
 * the API jar and on a running Folia server. The server-internal
 * {@link #REGIONIZED_SERVER_CLASS} is retained as the stricter runtime-only
 * confirmation (it exists only in the bundled server, not the API).
 */
public final class FoliaRuntimeDetector {

    /** Folia API class — present whenever the Folia API is on the classpath. */
    public static final String REGION_SCHEDULER_CLASS =
        "io.papermc.paper.threadedregions.scheduler.RegionScheduler";

    /** Folia server-internal class — present only on a running Folia server. */
    public static final String REGIONIZED_SERVER_CLASS =
        "io.papermc.paper.threadedregions.RegionizedServer";

    private FoliaRuntimeDetector() {
    }

    public static boolean isFoliaRuntime() {
        return isFoliaRuntime(Thread.currentThread().getContextClassLoader());
    }

    public static boolean isFoliaRuntime(ClassLoader classLoader) {
        return isPresent(REGION_SCHEDULER_CLASS, classLoader);
    }

    /**
     * Stricter check (no-arg): true only on an actual running Folia server (the
     * server-internal {@code RegionizedServer} is present), not merely when the
     * API is on the classpath. Uses the thread context class loader.
     *
     * <p>This is the correct discriminator for "am I on Folia vs vanilla Paper?"
     * because modern Paper ships the Folia <em>API</em> ({@link #REGION_SCHEDULER_CLASS}),
     * so {@link #isFoliaRuntime()} misfires {@code true} on Paper. Only a running
     * Folia server carries the server-internal {@code RegionizedServer}.
     */
    public static boolean isFoliaServer() {
        return isFoliaServer(Thread.currentThread().getContextClassLoader());
    }

    /**
     * Stricter check: true only on an actual running Folia server (the
     * server-internal {@code RegionizedServer} is present), not merely when the
     * API is on the classpath.
     */
    public static boolean isFoliaServer(ClassLoader classLoader) {
        return isPresent(REGIONIZED_SERVER_CLASS, classLoader);
    }

    private static boolean isPresent(String className, ClassLoader classLoader) {
        ClassLoader effectiveLoader = classLoader == null
            ? FoliaRuntimeDetector.class.getClassLoader() : classLoader;
        try {
            Class.forName(className, false, effectiveLoader);
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
