package org.nebula.plugin;

import org.nebula.core.vap.PluginTaskQueue;
import org.nebula.core.vap.VapApiInterceptor;
import org.nebula.core.vap.VapLevel;

import java.util.*;
import java.util.logging.Logger;

/**
 * Wires VAP plugin phase into the NebulaPlugin lifecycle (arch doc §13.2, P2.2.1).
 *
 * <p>This class bridges the gap between nebula-core's VAP infrastructure
 * ({@link org.nebula.core.vap.PluginTaskQueue}, {@link VapApiInterceptor})
 * and the Folia tick lifecycle. It:
 * <ol>
 *   <li>Creates a {@link PluginTaskQueue} with plugin registration order</li>
 *   <li>Creates a {@link VapApiInterceptor} backed by the queue</li>
 *   <li>Installs the interceptor so nebula-agent-rewritten Bukkit API calls
 *       are intercepted and enqueued</li>
 *   <li>Exposes a {@code /nebula vap test} command for compatibility checks</li>
 * </ol>
 *
 * <p>The JVM agent ({@code nebula-agent}) is responsible for bytecode-rewriting
 * plugin call sites to invoke {@link VapApiInterceptor#interceptAndWait}. The
 * {@link VapApiInterceptor} itself is server-side only and does NOT require
 * the agent to function — plugins running without the agent fall back to direct
 * execution (their calls run synchronously on the tick thread, bypassing the
 * DAG's plugin phase).
 */
public final class VapPluginPhase {

    private static final Logger LOG = Logger.getLogger(VapPluginPhase.class.getName());

    private final PluginTaskQueue taskQueue;
    private final VapApiInterceptor interceptor;
    private final VapPluginRegistry registry;

    public VapPluginPhase(List<String> pluginOrder) {
        this.taskQueue = new PluginTaskQueue(pluginOrder);
        this.interceptor = new VapApiInterceptor(taskQueue);
        this.registry = new VapPluginRegistry(pluginOrder);
    }

    /**
     * Returns the shared {@link PluginTaskQueue} for this server session.
     */
    public PluginTaskQueue taskQueue() {
        return taskQueue;
    }

    /**
     * Returns the {@link VapApiInterceptor} that nebula-agent rewritten call sites
     * invoke.
     */
    public VapApiInterceptor interceptor() {
        return interceptor;
    }

    /**
     * Returns the plugin compatibility registry.
     */
    public VapPluginRegistry registry() {
        return registry;
    }

    /**
     * Returns the number of plugin tasks pending in the current tick's queue.
     */
    public int pendingCount() {
        return taskQueue.pendingCount();
    }

    /**
     * Drains and executes all pending plugin tasks. Called once per tick after
     * all kernel DAG layers complete.
     *
     * @return number of tasks executed
     * @throws org.nebula.core.vap.PluginTaskException if any task fails
     */
    public int drainAndExecute() throws org.nebula.core.vap.PluginTaskException {
        return taskQueue.drainAndExecute();
    }

    /**
     * Registers a plugin's VAP compatibility level. Called during plugin load.
     */
    public void registerPlugin(String pluginName, VapLevel level) {
        registry.register(pluginName, level);
        LOG.info("VAP registered plugin '" + pluginName + "' at level " + level);
    }

    /**
     * Returns the VAP level for a registered plugin, or {@link VapLevel#LEVEL_0}
     * if the plugin has not registered a level (defaults to L0).
     */
    public VapLevel pluginLevel(String pluginName) {
        return registry.level(pluginName);
    }

    /**
     * Returns a summary of all registered plugins and their VAP levels.
     */
    public Map<String, VapLevel> pluginLevels() {
        return registry.allLevels();
    }

    // ── Plugin registry ────────────────────────────────────────────────────────

    /**
     * Tracks VAP compatibility level per plugin.
     */
    public static final class VapPluginRegistry {

        private final List<String> pluginOrder;
        private final Map<String, VapLevel> levels = new LinkedHashMap<>();

        public VapPluginRegistry(List<String> pluginOrder) {
            this.pluginOrder = List.copyOf(pluginOrder);
            // Default all plugins to LEVEL_0 (intercept + serialize)
            for (String p : pluginOrder) {
                levels.put(p, VapLevel.LEVEL_0);
            }
        }

        public void register(String pluginName, VapLevel level) {
            levels.put(pluginName, Objects.requireNonNull(level));
        }

        public VapLevel level(String pluginName) {
            return levels.getOrDefault(pluginName, VapLevel.LEVEL_0);
        }

        public Map<String, VapLevel> allLevels() {
            return Map.copyOf(levels);
        }

        public List<String> pluginOrder() {
            return pluginOrder;
        }
    }
}
