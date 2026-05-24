package org.nebula.core.vap;

import org.nebula.core.scheduler.TaskAction;

/**
 * A queued plugin operation awaiting execution in the plugin phase (arch doc §13.2).
 *
 * <p>When a plugin makes a synchronous Bukkit API call (e.g. entity.teleport()),
 * the call is intercepted, wrapped into a PluginTask, and submitted to the
 * plugin task queue. The calling virtual thread is suspended until the task
 * is executed in the plugin phase.
 *
 * @param pluginName  owning plugin's name (for ordering and diagnostics)
 * @param description human-readable description of the operation
 * @param action      the actual operation to perform
 * @param priority    execution priority within the plugin phase (lower = earlier)
 */
public record PluginTask(
    String pluginName,
    String description,
    TaskAction action,
    int priority
) {
    public PluginTask(String pluginName, String description, TaskAction action) {
        this(pluginName, description, action, 0);
    }
}
