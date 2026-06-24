package org.nebula.folia.bridge;

import org.nebula.guard.RWGuard;
import org.nebula.guard.RWGuardConfig;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Entry point for bootstrapping the Nebula subsystem into a running Folia server.
 *
 * <p>Typical usage (from a Folia plugin's {@code onEnable}):
 * <pre>{@code
 * NebulaFoliaBootstrap bootstrap = NebulaFoliaBootstrap
 *     .configure(RWGuardConfig.defaults())
 *     .withObserveMode()   // Phase 0: observe only
 *     .activate();
 * }</pre>
 *
 * <p>Integration sequence:
 * <ol>
 *   <li>Configure the RW guard (sampling rate, mode, violation log path).</li>
 *   <li>Register the {@link NeighborUpdateInterceptor} listener wired to
 *       {@link RedstoneTickHook#recordUpdate}.</li>
 *   <li>Register the {@link RedstoneTickHook.TaskResolver} with the
 *       component map from the loaded world data.</li>
 *   <li>Set the {@link RedstoneTickHook.TickExecutor} to invoke the
 *       {@link org.nebula.redstone.MicroStepScheduler}.</li>
 *   <li>Activate all hooks.</li>
 * </ol>
 */
public final class NebulaFoliaBootstrap {

    private static final Logger LOG = Logger.getLogger(NebulaFoliaBootstrap.class.getName());

    private final RWGuardConfig guardConfig;
    private NeighborUpdateInterceptor.Mode interceptMode = NeighborUpdateInterceptor.Mode.OBSERVE;
    private RedstoneTickHook.TaskResolver taskResolver = null;
    private RedstoneTickHook.TickExecutor tickExecutor = null;

    private NebulaFoliaBootstrap(RWGuardConfig guardConfig) {
        this.guardConfig = Objects.requireNonNull(guardConfig, "guardConfig");
    }

    public static NebulaFoliaBootstrap configure(RWGuardConfig guardConfig) {
        RWGuard.configure(guardConfig);
        return new NebulaFoliaBootstrap(guardConfig);
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /** Phase 0: Observe mode — Folia still executes, Nebula records. */
    public NebulaFoliaBootstrap withObserveMode() {
        this.interceptMode = NeighborUpdateInterceptor.Mode.OBSERVE;
        return this;
    }

    /** Phase 0 validation: Intercept mode — Nebula drives redstone propagation. */
    public NebulaFoliaBootstrap withInterceptMode() {
        this.interceptMode = NeighborUpdateInterceptor.Mode.INTERCEPT;
        return this;
    }

    public NebulaFoliaBootstrap withTaskResolver(RedstoneTickHook.TaskResolver resolver) {
        this.taskResolver = resolver;
        return this;
    }

    public NebulaFoliaBootstrap withTickExecutor(RedstoneTickHook.TickExecutor executor) {
        this.tickExecutor = executor;
        return this;
    }

    // ── Activation ────────────────────────────────────────────────────────────

    /**
     * Activates all Nebula hooks.  Call once during server startup.
     *
     * @return this instance (for method chaining / reference storage)
     */
    public NebulaFoliaBootstrap activate() {
        // Configure interceptor
        NeighborUpdateInterceptor.setMode(interceptMode);

        // Wire the interceptor listener to the tick hook
        // Use a single global bucket ("nebula-global") because the interceptor
        // runs on region threads while beginTick/endTick run on the global tick
        // thread.  Collecting all updates into one bucket avoids thread-handoff
        // complexity.  Region-aware partitioning happens downstream at the
        // FoliaRegionTickExecutor level, which dispatches each task to its
        // owning region thread via RegionScheduler.execute().
        NeighborUpdateInterceptor.addListener((worldName, x, y, z) -> {
            RedstoneTickHook.recordUpdate("nebula-global", worldName, x, y, z);
            // Only suppress if in INTERCEPT mode
            return interceptMode == NeighborUpdateInterceptor.Mode.INTERCEPT;
        });
        NeighborUpdateInterceptor.setActive(true);

        // Configure tick hook
        if (taskResolver != null) {
            RedstoneTickHook.setResolver(taskResolver);
        }
        if (tickExecutor != null) {
            RedstoneTickHook.setExecutor(tickExecutor);
        }
        RedstoneTickHook.setActive(true);

        // Register callback with NeighborUpdateHooks (agent bootstrap classloader).
        // We use reflection to cross the classloader boundary safely.
        // The plugin pushes its callback TO the hook — no classloader inversion.
        try {
            Class<?> hooksClass = Class.forName(
                "org.nebula.agent.NeighborUpdateHooks",
                true, ClassLoader.getSystemClassLoader());
            // Find NeighborUpdateCallback by name rather than assuming it is [0]
            Class<?> callbackInterface = null;
            for (Class<?> declared : hooksClass.getDeclaredClasses()) {
                if ("NeighborUpdateCallback".equals(declared.getSimpleName())) {
                    callbackInterface = declared;
                    break;
                }
            }
            if (callbackInterface == null) {
                throw new ClassNotFoundException(
                    "NeighborUpdateCallback not found among declared classes of NeighborUpdateHooks");
            }
            // Create the callback as an anonymous class visible to the system classloader
            // via our own interceptor which IS accessible (both in same classpath)
            Object callbackProxy = java.lang.reflect.Proxy.newProxyInstance(
                callbackInterface.getClassLoader(),
                new Class[]{ callbackInterface },
                (proxy, method, args) -> {
                    if ("onNeighborUpdate".equals(method.getName())) {
                        String worldName = (String) args[0];
                        int x = (int) args[1];
                        int y = (int) args[2];
                        int z = (int) args[3];
                        NeighborUpdateInterceptor.onNeighborUpdate(worldName, x, y, z);
                    }
                    return null;
                });
            hooksClass.getMethod("register", callbackInterface)
                      .invoke(null, callbackProxy);
            hooksClass.getMethod("setEnabled", boolean.class).invoke(null, true);
            LOG.info("NeighborUpdateHooks callback registered and enabled");
        } catch (ClassNotFoundException e) {
            LOG.warning("NeighborUpdateHooks not found — run with -javaagent:nebula-agent.jar");
        } catch (Exception e) {
            LOG.warning("Failed to register NeighborUpdateHooks callback: " + e);
        }

        LOG.info("Nebula Folia bootstrap activated (mode=" + interceptMode
            + ", guard=" + guardConfig.mode() + ")");
        return this;
    }

    /** Deactivates all hooks (for clean shutdown or hot-reload). */
    public void deactivate() {
        NeighborUpdateInterceptor.setActive(false);
        RedstoneTickHook.setActive(false);
        LOG.info("Nebula Folia bootstrap deactivated");
    }

    public RWGuardConfig guardConfig() {
        return guardConfig;
    }
}
