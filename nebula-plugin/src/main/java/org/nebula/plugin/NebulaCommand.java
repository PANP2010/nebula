package org.nebula.plugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * Commands for Nebula capture harness control.
 *
 * <p>Commands:
 * <ul>
 *   <li>{@code /nebula capture start <ticks>} — starts capture for N ticks</li>
 *   <li>{@code /nebula capture stop} — stops capture and reports frame count</li>
 *   <li>{@code /nebula status} — shows plugin status (running, capture active)</li>
 * </ul>
 */
public final class NebulaCommand implements CommandExecutor, TabExecutor {

    private static final Logger LOG = Logger.getLogger(NebulaCommand.class.getName());
    private final NebulaPlugin plugin;

    public NebulaCommand(NebulaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd,
                             String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "capture" -> handleCapture(sender, args);
            case "status" -> handleStatus(sender);
            case "scan" -> handleScan(sender);
            case "help" -> sendHelp(sender);
            default -> sender.sendMessage("§cUnknown subcommand: " + sub);
        }
        return true;
    }

    private void handleScan(CommandSender sender) {
        if (!sender.hasPermission("nebula.status")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return;
        }
        int dispatched = plugin.rescanLoadedChunks();
        sender.sendMessage("§aRescan dispatched for " + dispatched
            + " loaded chunk(s). Registered components: " + plugin.componentCount()
            + " (updates asynchronously — run /nebula status shortly).");
        LOG.info("Manual rescan requested by " + sender.getName()
            + " (" + dispatched + " chunks dispatched)");
    }

    private void handleCapture(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nebula.capture")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage("§cUsage: /nebula capture <start|stop> [ticks]");
            return;
        }

        String action = args[1].toLowerCase();
        switch (action) {
            case "start" -> {
                long ticks = 1000; // default
                if (args.length >= 3) {
                    try {
                        ticks = Long.parseLong(args[2]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage("§cInvalid tick count: " + args[2]);
                        return;
                    }
                }
                plugin.startCapture(ticks);
                sender.sendMessage("§aCapture started for " + ticks + " ticks");
                LOG.info("Capture started by " + sender.getName() + " for " + ticks + " ticks");
            }
            case "stop" -> {
                var frames = plugin.stopCapture();
                sender.sendMessage("§aCapture stopped: " + frames.size() + " frames recorded");
                LOG.info("Capture stopped by " + sender.getName() + ": " + frames.size() + " frames");
            }
            default -> sender.sendMessage("§cUnknown capture action: " + action);
        }
    }

    private void handleStatus(CommandSender sender) {
        sender.sendMessage("§6Nebula Status:");
        sender.sendMessage("  §7Registered redstone components: §f" + plugin.componentCount());
        sender.sendMessage("  §7RedstoneWorldState: §f" + plugin.redstoneState().size() + " entries");
        sender.sendMessage("  §7EntityPhysicsState: §f" + plugin.entityState().size() + " entries");
        sender.sendMessage("  §7BlockEntityState: §f" + plugin.blockEntityState().size() + " entries");
        sender.sendMessage("  §7StateHasher tracked: §f" + plugin.stateHasher().trackedPositions().size() + " positions");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6Nebula Commands:");
        sender.sendMessage("  §e/nebula capture start [ticks] §7- Start state capture");
        sender.sendMessage("  §e/nebula capture stop §7- Stop capture and report");
        sender.sendMessage("  §e/nebula scan §7- Rescan loaded chunks for redstone components");
        sender.sendMessage("  §e/nebula status §7- Show plugin status");
        sender.sendMessage("  §e/nebula help §7- Show this help");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender,
                                      Command cmd,
                                      String alias,
                                      String[] args) {
        if (args.length == 1) {
            return Arrays.asList("capture", "status", "scan", "help");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("capture")) {
            return Arrays.asList("start", "stop");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("capture") && args[1].equalsIgnoreCase("start")) {
            return Arrays.asList("100", "1000", "5000", "10000");
        }
        return List.of();
    }
}