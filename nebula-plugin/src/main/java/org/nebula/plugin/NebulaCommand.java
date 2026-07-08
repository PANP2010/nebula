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
 *   <li>{@code /nebula perf [reset]} — shows DAG tick timing percentiles</li>
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
            case "perf" -> handlePerf(sender, args);
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

                // Summarise the recorded hashes so zero-diff behaviour is
                // observable: how many DISTINCT state hashes appeared, plus the
                // first/last for eyeballing.  A static world yields 1 distinct
                // hash; a world with redstone activity yields several.
                long distinct = frames.stream()
                    .map(org.nebula.replay.ReplayFrame::stateHashHex)
                    .distinct().count();
                sender.sendMessage("  §7Distinct state hashes: §f" + distinct);
                if (!frames.isEmpty()) {
                    sender.sendMessage("  §7First: §f" + shortHash(frames.get(0).stateHashHex()));
                    sender.sendMessage("  §7Last:  §f" + shortHash(frames.get(frames.size() - 1).stateHashHex()));
                }

                // Persist to disk so runs can be compared for zero-diff.
                String saved = plugin.saveLastCapture();
                if (saved != null) {
                    sender.sendMessage("  §7Saved: §f" + saved);
                }
                LOG.info("Capture stopped by " + sender.getName() + ": " + frames.size()
                    + " frames, " + distinct + " distinct hashes"
                    + (saved != null ? ", saved to " + saved : ""));
            }
            default -> sender.sendMessage("§cUnknown capture action: " + action);
        }
    }

    private static String shortHash(String hex) {
        return hex.length() <= 16 ? hex : hex.substring(0, 16) + "…";
    }

    private void handleStatus(CommandSender sender) {
        sender.sendMessage("§6Nebula Status:");
        sender.sendMessage("  §7Registered redstone components: §f" + plugin.componentCount());
        sender.sendMessage("  §7RedstoneWorldState: §f" + plugin.redstoneState().size() + " entries");
        sender.sendMessage("  §7EntityPhysicsState: §f" + plugin.entityState().size() + " entries");
        sender.sendMessage("  §7BlockEntityState: §f" + plugin.blockEntityState().size() + " entries");
        sender.sendMessage("  §7StateHasher tracked: §f" + plugin.stateHasher().trackedPositions().size() + " positions");
    }

    private void handlePerf(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nebula.status")) {
            sender.sendMessage("§cYou don't have permission to view Nebula performance.");
            return;
        }
        if (args.length >= 2 && args[1].equalsIgnoreCase("reset")) {
            plugin.tickTimeRecorder().reset();
            sender.sendMessage("§aNebula DAG tick metrics reset.");
            return;
        }
        var s = plugin.tickTimeRecorder().snapshot();
        sender.sendMessage("§6Nebula DAG tick performance:");
        if (s.count() == 0) {
            sender.sendMessage("  §7No DAG ticks recorded yet. Place redstone and run /nebula scan.");
            return;
        }
        sender.sendMessage(String.format("  §7Ticks recorded: §f%d §7(window %d)",
            s.count(), s.windowSize()));
        sender.sendMessage(String.format("  §7avg §f%.3f ms  §7min §f%.3f ms  §7max §f%.3f ms",
            s.avgMs(), s.minMs(), s.maxMs()));
        sender.sendMessage(String.format("  §7p50 §f%.3f ms  §7p95 §f%.3f ms  §7p99 §f%.3f ms",
            s.p50Ms(), s.p95Ms(), s.p99Ms()));
        // 50ms is the 20-TPS budget for the whole server tick; the DAG is only
        // part of that, so flag when a single p99 DAG tick alone eats the budget.
        if (s.p99Ms() >= 50.0) {
            sender.sendMessage("  §cWARNING: p99 DAG tick alone exceeds the 50ms/20-TPS budget.");
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6Nebula Commands:");
        sender.sendMessage("  §e/nebula capture start [ticks] §7- Start state capture");
        sender.sendMessage("  §e/nebula capture stop §7- Stop capture and report");
        sender.sendMessage("  §e/nebula scan §7- Rescan loaded chunks for redstone components");
        sender.sendMessage("  §e/nebula status §7- Show plugin status");
        sender.sendMessage("  §e/nebula perf [reset] §7- Show DAG tick timing percentiles");
        sender.sendMessage("  §e/nebula help §7- Show this help");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender,
                                      Command cmd,
                                      String alias,
                                      String[] args) {
        if (args.length == 1) {
            return Arrays.asList("capture", "status", "scan", "perf", "help");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("capture")) {
            return Arrays.asList("start", "stop");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("perf")) {
            return List.of("reset");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("capture") && args[1].equalsIgnoreCase("start")) {
            return Arrays.asList("100", "1000", "5000", "10000");
        }
        return List.of();
    }
}