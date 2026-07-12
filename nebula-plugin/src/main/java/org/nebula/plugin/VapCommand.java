package org.nebula.plugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.nebula.core.vap.VapLevel;

import java.util.*;
import java.util.stream.Collectors;

/**
 * /nebula vap command — VAP plugin compatibility management (arch doc §13.2, P2.2.5).
 *
 * <p>Subcommands:
 * <ul>
 *   <li>{@code /nebula vap} — show all registered plugins and their VAP levels</li>
 *   <li>{@code /nebula vap test <plugin>} — run L0 compatibility test for a plugin</li>
 *   <li>{@code /nebula vap set <plugin> <level>} — set a plugin's VAP level</li>
 *   <li>{@code /nebula vap status} — show VAP phase status (active/inactive, queue depth)</li>
 * </ul>
 */
public final class VapCommand implements CommandExecutor, TabCompleter {

    private final VapPluginPhase vapPhase;

    public VapCommand(VapPluginPhase vapPhase) {
        this.vapPhase = Objects.requireNonNull(vapPhase);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        if (args.length == 0) {
            sendStatus(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "status" -> sendStatus(sender);
            case "list" -> sendList(sender);
            case "test" -> {
                if (args.length < 2) {
                    sender.sendMessage("Usage: /nebula vap test <plugin>");
                    return true;
                }
                testPlugin(sender, args[1]);
            }
            case "set" -> {
                if (args.length < 3) {
                    sender.sendMessage("Usage: /nebula vap set <plugin> <L0|L1|L2|SANDBOXED>");
                    return true;
                }
                setLevel(sender, args[1], args[2]);
            }
            case "queue" -> sendQueueStatus(sender);
            default -> sender.sendMessage("Unknown subcommand: " + args[0]
                + ". Use: status | list | test | set | queue");
        }
        return true;
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage("=== VAP Status ===");
        sender.sendMessage("VAP phase: " + (vapPhase != null ? "ACTIVE" : "INACTIVE"));
        if (vapPhase != null) {
            sender.sendMessage("Pending tasks: " + vapPhase.pendingCount());
            sender.sendMessage("Registered plugins: " + vapPhase.registry().pluginOrder().size());
        }
    }

    private void sendList(CommandSender sender) {
        sender.sendMessage("=== VAP Plugin Levels ===");
        if (vapPhase == null) {
            sender.sendMessage("(VAP not initialized)");
            return;
        }
        Map<String, VapLevel> levels = vapPhase.pluginLevels();
        if (levels.isEmpty()) {
            sender.sendMessage("No plugins registered");
            return;
        }
        for (var e : levels.entrySet()) {
            sender.sendMessage("  " + e.getKey() + ": " + e.getValue());
        }
    }

    private void testPlugin(CommandSender sender, String pluginName) {
        VapLevel level = vapPhase.pluginLevel(pluginName);
        sender.sendMessage("Testing plugin: " + pluginName);
        sender.sendMessage("Current VAP level: " + level);
        sender.sendMessage("[Placeholder] Running L0 compatibility test...");
        sender.sendMessage("(Full test harness requires loading the plugin and running sample operations)");
    }

    private void setLevel(CommandSender sender, String pluginName, String levelStr) {
        try {
            VapLevel level = VapLevel.valueOf(levelStr.toUpperCase());
            vapPhase.registerPlugin(pluginName, level);
            sender.sendMessage("Set " + pluginName + " → " + level);
        } catch (IllegalArgumentException e) {
            sender.sendMessage("Unknown VAP level: " + levelStr
                + ". Valid: L0, L1, L2, SANDBOXED");
        }
    }

    private void sendQueueStatus(CommandSender sender) {
        sender.sendMessage("=== VAP Queue ===");
        sender.sendMessage("Pending: " + vapPhase.pendingCount());
        sender.sendMessage("(Queue drains at end of each tick, after all kernel DAG layers)");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender,
                                                  Command command,
                                                  String alias,
                                                  String[] args) {
        if (args.length == 1) {
            return list("status", "list", "test", "set", "queue");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("test")) {
            return vapPhase.registry().pluginOrder().stream()
                .filter(p -> p.toLowerCase().startsWith(args[1].toLowerCase()))
                .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("set")) {
            return vapPhase.registry().pluginOrder().stream()
                .filter(p -> p.toLowerCase().startsWith(args[1].toLowerCase()))
                .collect(Collectors.toList());
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("set")) {
            return list("L0", "L1", "L2", "SANDBOXED");
        }
        return Collections.emptyList();
    }

    private static List<String> list(String... values) {
        return Arrays.asList(values);
    }
}
