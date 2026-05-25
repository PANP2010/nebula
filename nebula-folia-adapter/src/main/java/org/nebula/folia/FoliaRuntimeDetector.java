package org.nebula.folia;

public final class FoliaRuntimeDetector {
    public static final String REGIONIZED_SERVER_CLASS = "io.papermc.paper.threadedregions.RegionizedServer";

    private FoliaRuntimeDetector() {
    }

    public static boolean isFoliaRuntime() {
        return isFoliaRuntime(Thread.currentThread().getContextClassLoader());
    }

    public static boolean isFoliaRuntime(ClassLoader classLoader) {
        ClassLoader effectiveLoader = classLoader == null ? FoliaRuntimeDetector.class.getClassLoader() : classLoader;
        try {
            Class.forName(REGIONIZED_SERVER_CLASS, false, effectiveLoader);
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
