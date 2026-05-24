package org.nebula.core.vap;

/**
 * Thrown when a plugin task fails during execution in the plugin phase.
 */
public final class PluginTaskException extends Exception {
    private final String pluginName;
    private final String taskDescription;

    public PluginTaskException(String pluginName, String taskDescription, Throwable cause) {
        super("Plugin '" + pluginName + "' task failed: " + taskDescription, cause);
        this.pluginName = pluginName;
        this.taskDescription = taskDescription;
    }

    public String pluginName() {
        return pluginName;
    }

    public String taskDescription() {
        return taskDescription;
    }
}
