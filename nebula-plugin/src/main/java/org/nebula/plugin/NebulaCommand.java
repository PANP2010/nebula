package org.nebula.plugin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.nebula.folia.bridge.NeighborUpdateInterceptor;
import org.nebula.folia.bridge.RedstoneTickHook;
import org.nebula.guard.RWGuard;
import org.nebula.redstone.RedstoneComponentType;
import org.nebula.core.state.WorldPos;

import java.nio.file.Path;
import java.util.Map;

/**
 * /nebula command handler providing runtime diagnostics (arch doc §15.3).
 *
 * Commands:
 *   /nebula status                     — interceptor mode, guard mode, component count
 *   /nebula guard                      — current RW guard sampling stats
 *   /nebula mode observe|intercept     — switch interceptor mode
 *   /nebula components                 — list known component positions
 *   /nebula shadow                     — shadow DAG execution statistics
 *   /nebula dg1 [start [ticks]]        — DG1 idempotency verification status / start
 *   /nebula tick sprint <ticks> [burst]— accelerate DG1/replay N logical ticks
 *   /nebula tick stop                  — stop sprint
 *   /nebula tick status                — sprint progress
 *   /nebula replay start [ticks]       — start recording a replay
 *   /nebula replay stop                — stop and save reference replay
 *   /nebula replay status              — show replay recording status
 *   /nebula replay compare             — compare candidate vs reference
 */
public final class NebulaCommand implements CommandExecutor {

    private final NebulaPlugin plugin;
    private final Map<WorldPos, RedstoneComponentType> componentMap;
    private final ChunkRedstoneScanner scanner;

    public NebulaCommand(NebulaPlugin plugin,
                         Map<WorldPos, RedstoneComponentType> componentMap,
                         ChunkRedstoneScanner scanner) {
        this.plugin = plugin;
        this.componentMap = componentMap;
        this.scanner = scanner;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("nebula.admin") && !sender.isOp()) {
            sender.sendMessage("§cNo permission.");
            return true;
        }

        if (args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            sendStatus(sender);
            return true;
        }

        return switch (args[0].toLowerCase()) {
            case "guard"      -> { sendGuardStatus(sender); yield true; }
            case "components" -> { sendComponents(sender); yield true; }
            case "mode"       -> { handleMode(sender, args); yield true; }
            case "shadow"     -> { sendShadowStatus(sender); yield true; }
            case "dg1"        -> { sendDg1Status(sender, args); yield true; }
            case "tick"       -> { handleTick(sender, args); yield true; }
            case "intercept"  -> { handleIntercept(sender, args); yield true; }
            case "bench"      -> { handleBench(sender, args); yield true; }
            case "replay"     -> { handleReplay(sender, args); yield true; }
            default -> {
                sender.sendMessage("§eUsage: /nebula <status|guard|components|mode|shadow|dg1|tick|intercept|bench|replay>");
                yield true;
            }
        };
    }

    // ── Sub-command handlers ──────────────────────────────────────────────────

    private void sendStatus(CommandSender sender) {
        sender.sendMessage("§6=== Nebula Status ===");
        sender.sendMessage("§7Interceptor mode: §f" + NeighborUpdateInterceptor.getMode().name());
        sender.sendMessage("§7Interceptor active: §f" + NeighborUpdateInterceptor.isActive());
        sender.sendMessage("§7Tick hook active:   §f" + RedstoneTickHook.isActive());
        sender.sendMessage("§7Known components:  §f" + componentMap.size());
        sender.sendMessage("§7Chunks scanned:    §f" + (scanner != null ? scanner.chunksScanned() : 0));
        sender.sendMessage("§7Guard config:       §f"
            + plugin.bootstrap().guardConfig().mode().name()
            + " (sampling=" + String.format("%.0f%%",
                plugin.bootstrap().guardConfig().samplingRate() * 100) + ")");
        ReplaySession rs = plugin.replaySession();
        if (rs != null) {
            sender.sendMessage("§7Replay recording:  §f" + rs.isRecording()
                + " (" + rs.ticksRecorded() + " ticks)");
        }
    }

    private void sendGuardStatus(CommandSender sender) {
        sender.sendMessage("§6=== RW Guard ===");
        sender.sendMessage("§7Mode: §f" + RWGuard.config().mode());
        sender.sendMessage("§7Sampling: §f"
            + String.format("%.1f%%", RWGuard.config().samplingRate() * 100));
        sender.sendMessage("§7Enabled: §f" + RWGuard.config().enabled());
    }

    private void sendComponents(CommandSender sender) {
        if (componentMap.isEmpty()) {
            sender.sendMessage("§7No components registered yet. Walk around to load chunks.");
            return;
        }
        sender.sendMessage("§6Components (" + componentMap.size() + "):");
        componentMap.entrySet().stream().limit(20).forEach(e ->
            sender.sendMessage("§7  " + e.getValue().name()
                + " @ " + e.getKey().x() + "," + e.getKey().y() + "," + e.getKey().z()));
        if (componentMap.size() > 20) {
            sender.sendMessage("§7  ... and " + (componentMap.size() - 20) + " more");
        }
    }

    private void handleMode(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§eUsage: /nebula mode <observe|intercept>");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "observe" -> {
                NeighborUpdateInterceptor.setMode(NeighborUpdateInterceptor.Mode.OBSERVE);
                sender.sendMessage("§aNebula switched to OBSERVE mode.");
            }
            case "intercept" -> {
                NeighborUpdateInterceptor.setMode(NeighborUpdateInterceptor.Mode.INTERCEPT);
                sender.sendMessage("§cNebula switched to INTERCEPT mode. "
                    + "Folia propagation is now suppressed.");
            }
            default -> sender.sendMessage("§eUnknown mode: " + args[1]);
        }
    }

    private void sendShadowStatus(CommandSender sender) {
        ShadowExecutionMonitor mon = plugin.shadowMonitor();
        MsptMonitor mspt = plugin.msptMonitor();
        if (mon == null || mon.ticks() == 0) {
            sender.sendMessage("§7Shadow DAG: no ticks recorded yet.");
            return;
        }
        sender.sendMessage("§6=== Shadow DAG ===");
        sender.sendMessage("§7" + mon.summary());
        sender.sendMessage(String.format("§7MSPT: avg §f%.3f ms §7/ peak §f%.3f ms",
            mspt.avgMs(), mspt.peakMs()));
    }

    private void sendDg1Status(CommandSender sender, String[] args) {
        Dg1Verifier dg1 = plugin.dg1Verifier();
        if (dg1 == null) {
            sender.sendMessage("§cDG1 verifier not available.");
            return;
        }
        // /nebula dg1 start [ticks]
        if (args.length >= 2 && "start".equalsIgnoreCase(args[1])) {
            long ticks = args.length >= 3 ? Long.parseLong(args[2]) : 10_000L;
            dg1.startVerification(ticks);
            sender.sendMessage("§aDG1 verification started (" + ticks + " ticks).");
            return;
        }
        sender.sendMessage("§6=== DG1 Idempotency ===");
        sender.sendMessage("§7Active:    §f" + dg1.isActive());
        sender.sendMessage("§7Verified:  §f" + dg1.ticksVerified() + " ticks");
        sender.sendMessage("§7Mismatches:§f" + dg1.mismatches());
        if (!dg1.isActive() && dg1.ticksVerified() > 0) {
            sender.sendMessage(dg1.mismatches() == 0
                ? "§aDG1 PASS — hash function is deterministic"
                : "§cDG1 FAIL — " + dg1.mismatches() + " hash mismatches");
        }
    }

    /**
     * /nebula bench — MSPT benchmark.
     *   start [window]  — start benchmark (default 200-tick window per phase)
     *   status          — show current progress / result
     */
    private void handleBench(CommandSender sender, String[] args) {
        BenchmarkSession bench = plugin.benchSession();
        MsptMonitor mspt = plugin.msptMonitor();
        if (bench == null || mspt == null) {
            sender.sendMessage("§cBenchmark not available.");
            return;
        }
        if (args.length < 2 || "start".equalsIgnoreCase(args[1])) {
            bench.start();
            sender.sendMessage("§6Bench: measuring shadow DAG overhead. Use /nebula bench status after redstone activity.");
            return;
        }
        if ("status".equalsIgnoreCase(args[1])) {
            sender.sendMessage("§6=== Bench Result ===");
            sender.sendMessage("§7" + bench.report());
            double avg  = bench.shadowAvgMs();
            double peak = bench.shadowPeakMs();
            boolean pass = avg < 5.0 && peak < 15.0;
            sender.sendMessage(pass
                ? "§aPASS: shadow overhead safe for Phase 0"
                : "§eWARN: avg=" + String.format("%.2f", avg) + "ms peak=" + String.format("%.2f", peak) + "ms");
            return;
        }
        sender.sendMessage("§eUsage: /nebula bench [start|status]");
    }

    private void handleIntercept(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§eUsage: /nebula intercept <start [ticks]|stop|compare|status>");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "start" -> {
                long ticks = args.length >= 3 ? Long.parseLong(args[2]) : 1000L;

                if (plugin.referenceReplay() == null) {
                    sender.sendMessage("§cNo reference replay yet. Wait for auto-recording or /nebula replay stop.");
                    return;
                }

                // Switch to INTERCEPT mode
                plugin.switchToInterceptMode();

                // Record candidate over real ticks (not sprint — world must actually tick)
                ReplaySession session = plugin.replaySession();
                if (session != null) {
                    plugin.getDataFolder().mkdirs();
                    java.nio.file.Path savePath = plugin.getDataFolder().toPath()
                        .resolve("replay-intercept.replay");
                    session.startRecording(ticks, savePath);
                }

                sender.sendMessage(String.format(
                    "§cINTERCEPT mode active. Recording %d real ticks. DAG suppresses Folia.", ticks));
                plugin.getLogger().info("INTERCEPT validation started: " + ticks + " real ticks");
            }
            case "stop" -> {
                plugin.switchToObserveMode();
                ReplaySession session = plugin.replaySession();
                if (session != null && session.isRecording()) session.stopRecording(null);
                sender.sendMessage("§aSwitched back to OBSERVE mode.");
            }
            case "compare" -> {
                InterceptMonitor mon = plugin.interceptMonitor();
                plugin.switchToObserveMode();
                sender.sendMessage("§6=== INTERCEPT Phase 0 Validation ===");
                if (mon == null || mon.interceptTicks() == 0) {
                    sender.sendMessage("§eNo intercept data yet. Run /nebula intercept start first.");
                    return;
                }
                boolean pass = mon.dagErrors() == 0 && mon.suppressedUpdates() > 0;
                sender.sendMessage((pass ? "§a" : "§c") + (pass ? "PASS" : "FAIL"));
                sender.sendMessage("§7" + mon.summary());
                if (mon.suppressedUpdates() == 0) {
                    sender.sendMessage("§cFAIL: No updates suppressed — check hook is firing");
                } else if (mon.dagErrors() > 0) {
                    sender.sendMessage("§cFAIL: " + mon.dagErrors() + " DAG errors — check logs");
                } else {
                    sender.sendMessage("§aPASS: " + mon.suppressedUpdates()
                        + " updates suppressed, 0 errors over "
                        + mon.interceptTicks() + " ticks");
                }
                plugin.getLogger().info("INTERCEPT Phase 0: " + mon.summary());
            }
            case "status" -> {
                sender.sendMessage("§6=== INTERCEPT Status ===");
                sender.sendMessage("§7Mode: §f" + org.nebula.folia.bridge.NeighborUpdateInterceptor.getMode().name());
                ReplaySession session = plugin.replaySession();
                if (session != null) {
                    sender.sendMessage("§7Candidate: §f" + session.isRecording()
                        + " (" + session.ticksRecorded() + " ticks, " + session.frames().size() + " frames)");
                }
                sender.sendMessage("§7Reference: §f" + (plugin.referenceReplay() != null
                    ? plugin.referenceReplay().size() + " frames" : "none"));
            }
            default -> sender.sendMessage("§eUsage: /nebula intercept <start [ticks]|stop|compare|status>");
        }
    }

    private void handleTick(CommandSender sender, String[] args) {
        TickSprinter sprinter = plugin.tickSprinter();
        if (sprinter == null) {
            sender.sendMessage("§cTickSprinter not available.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§eUsage: /nebula tick <sprint <ticks> [burst]|stop|status>");
            return;
        }
        switch (args[1].toLowerCase()) {
            case "sprint" -> {
                long ticks = args.length >= 3 ? Long.parseLong(args[2]) : 10_000L;
                int burst  = args.length >= 4 ? Integer.parseInt(args[3]) : 50;
                sprinter.start(ticks, burst);
                sender.sendMessage(String.format("§aTick sprint started: %d ticks, burst=%d/real-tick", ticks, burst));
            }
            case "stop" -> {
                sprinter.stop();
                sender.sendMessage("§eTick sprint stopped.");
            }
            case "status" -> {
                if (!sprinter.isActive()) {
                    sender.sendMessage("§7Sprint: inactive. Last run: " + sprinter.sprintDone() + " ticks.");
                } else {
                    sender.sendMessage(String.format("§6Sprint: %d/%d ticks (burst=%d/real-tick)",
                        sprinter.sprintDone(), sprinter.sprintTarget(), sprinter.burstPerTick()));
                }
            }
            default -> sender.sendMessage("§eUsage: /nebula tick <sprint <ticks> [burst]|stop|status>");
        }
    }

    private void handleReplay(CommandSender sender, String[] args) {
        ReplaySession session = plugin.replaySession();
        if (session == null) {
            sender.sendMessage("§cReplay session not available.");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage("§eUsage: /nebula replay <start [ticks]|stop|status|compare>");
            return;
        }

        switch (args[1].toLowerCase()) {
            case "start" -> {
                long ticks = args.length >= 3 ? Long.parseLong(args[2]) : 1000L;
                session.startRecording(ticks);
                String msg = "Replay recording started for " + ticks + " ticks.";
                sender.sendMessage("§a" + msg);
                plugin.getLogger().info(msg);
            }
            case "stop" -> {
                if (!session.isRecording()) {
                    sender.sendMessage("§eNot currently recording.");
                    return;
                }
                plugin.getDataFolder().mkdirs();
                Path savePath = plugin.getDataFolder().toPath().resolve("replay-reference.replay");
                session.stopRecording(savePath);
                plugin.setReferenceReplay(session.frames());
                String msg = "Recording stopped. " + session.ticksRecorded()
                    + " frames saved to plugins/Nebula/replay-reference.replay";
                sender.sendMessage("§a" + msg);
                plugin.getLogger().info(msg);
            }
            case "status" -> {
                String msg = "Replay: recording=" + session.isRecording()
                    + " ticks=" + session.ticksRecorded()
                    + " ref=" + (plugin.referenceReplay() != null ? plugin.referenceReplay().size() : "none");
                sender.sendMessage("§6" + msg);
                plugin.getLogger().info(msg);
            }
            case "compare" -> {
                if (plugin.referenceReplay() == null) {
                    sender.sendMessage("§cNo reference replay. Record one first with /nebula replay start");
                    return;
                }
                if (session.frames().isEmpty()) {
                    sender.sendMessage("§cNo candidate frames to compare.");
                    return;
                }
                String result = session.compareWith(plugin.referenceReplay());
                for (String line : result.split("\n")) {
                    sender.sendMessage((result.startsWith("PASS") ? "§a" : "§c") + line);
                }
            }
            default -> sender.sendMessage("§eUnknown sub-command: " + args[1]);
        }
    }
}
