package org.nebula.core.vap;

/**
 * Thrown when a sandbox request times out waiting for kernel response.
 */
public final class SandboxTimeoutException extends Exception {
    private final String pluginName;
    private final long timeoutMs;

    public SandboxTimeoutException(String pluginName, long timeoutMs) {
        super("Plugin '" + pluginName + "' sandbox request timed out after " + timeoutMs + "ms");
        this.pluginName = pluginName;
        this.timeoutMs = timeoutMs;
    }

    public String pluginName() {
        return pluginName;
    }

    public long timeoutMs() {
        return timeoutMs;
    }
}
