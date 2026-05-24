package org.nebula.core.vap;

/**
 * Thrown when a VAP Level 0 intercepted API call fails.
 */
public final class VapInterceptException extends Exception {

    private final String pluginName;
    private final String operation;

    public VapInterceptException(String pluginName, String operation, String message) {
        super("Plugin '" + pluginName + "' intercepted call '" + operation + "' failed: " + message);
        this.pluginName = pluginName;
        this.operation = operation;
    }

    public VapInterceptException(String pluginName, String operation, Throwable cause) {
        super("Plugin '" + pluginName + "' intercepted call '" + operation + "' failed: " + cause.getMessage(), cause);
        this.pluginName = pluginName;
        this.operation = operation;
    }

    public String pluginName() {
        return pluginName;
    }

    public String operation() {
        return operation;
    }
}
