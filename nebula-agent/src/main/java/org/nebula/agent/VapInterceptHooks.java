package org.nebula.agent;

/**
 * Hook methods invoked by VapApiInterceptTransformer-rewritten bytecode.
 *
 * <p>When a plugin calls a Bukkit API write method, the transformer inserts
 * calls to beforeApiCall/afterApiCall around the original invocation. These
 * hooks notify the VapApiInterceptor to suspend the virtual thread if needed.
 *
 * <p>The actual interceptor instance is registered at runtime via {@link #setInterceptor}.
 */
public final class VapInterceptHooks {

    private static volatile Interceptor interceptor;

    private VapInterceptHooks() {}

    /**
     * Called by the Nebula plugin at startup to wire the hooks to the actual
     * VapApiInterceptor instance.
     */
    public static void setInterceptor(Interceptor impl) {
        interceptor = impl;
    }

    /**
     * Inserted before a Bukkit API write call.
     */
    public static void beforeApiCall(String pluginClass, String apiMethod) {
        Interceptor i = interceptor;
        if (i != null) {
            i.beforeApiCall(pluginClass, apiMethod);
        }
    }

    /**
     * Inserted after a Bukkit API write call.
     */
    public static void afterApiCall(String pluginClass, String apiMethod) {
        Interceptor i = interceptor;
        if (i != null) {
            i.afterApiCall(pluginClass, apiMethod);
        }
    }

    /**
     * Interface for the runtime interceptor implementation.
     * Implemented by VapApiInterceptor in nebula-core.
     */
    public interface Interceptor {
        void beforeApiCall(String pluginClass, String apiMethod);
        void afterApiCall(String pluginClass, String apiMethod);
    }
}
