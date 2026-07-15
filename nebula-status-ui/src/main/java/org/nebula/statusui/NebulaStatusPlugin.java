package org.nebula.statusui;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bukkit plugin that launches the Nebula Status Dashboard GUI.
 *
 * <p>Usage:
 * <ul>
 *   <li>{@code /nebula-status} - Shows help</li>
 *   <li>{@code /nebula-status open} - Opens the status dashboard window</li>
 * </ul>
 *
 * <p>This plugin requires the nebula-plugin to be installed and running.
 */
public final class NebulaStatusPlugin extends JavaPlugin {

    private static NebulaStatusPlugin instance;
    private NebulaStatusWindow statusWindow;
    private StatusProvider statusProvider;

    @Override
    public void onEnable() {
        instance = this;

        // Register command
        getCommand("nebula-status").setExecutor((sender, command, label, args) -> {
            if (!sender.hasPermission("nebula.status")) {
                sender.sendMessage("§cYou don't have permission to use this command.");
                return true;
            }

            if (args.length > 0 && "open".equalsIgnoreCase(args[0])) {
                openDashboard();
            } else {
                sender.sendMessage("§6┌─ Nebula Status Dashboard ─────────────────┐");
                sender.sendMessage("§6│ §f/nebula-status open §7- §fOpen the status dashboard GUI");
                sender.sendMessage("§6│");
                sender.sendMessage("§6│ §7The dashboard displays:");
                sender.sendMessage("§6│ §7  • Real-time DAG execution metrics");
                sender.sendMessage("§6│ §7  • Tick time and microstep charts");
                sender.sendMessage("§6│ §7  • Component health indicators");
                sender.sendMessage("§6│ §7  • Server information");
                sender.sendMessage("§6└───────────────────────────────────────────┘");
            }
            return true;
        });

        // Try to connect to the main nebula plugin
        connectToNebulaPlugin();

        getLogger().info("Nebula Status Dashboard enabled. Use /nebula-status open to launch the GUI.");
    }

    @Override
    public void onDisable() {
        if (statusWindow != null) {
            statusWindow.close();
        }
        getLogger().info("Nebula Status Dashboard disabled.");
    }

    private void connectToNebulaPlugin() {
        var pluginManager = getServer().getPluginManager();
        var nebulaPlugin = pluginManager.getPlugin("Nebula");

        if (nebulaPlugin != null && nebulaPlugin.isEnabled()) {
            getLogger().info("Connected to Nebula plugin");
            statusProvider = new StatusProviderAdapter(nebulaPlugin);
        } else {
            getLogger().warning("Nebula plugin not found - dashboard will show placeholder data");
            statusProvider = new MockStatusProvider();
        }
    }

    public void openDashboard() {
        if (statusWindow == null) {
            statusWindow = new NebulaStatusWindow(statusProvider);
        }
        statusWindow.show();
    }

    public static NebulaStatusPlugin getInstance() {
        return instance;
    }

    public StatusProvider getStatusProvider() {
        return statusProvider;
    }
}
