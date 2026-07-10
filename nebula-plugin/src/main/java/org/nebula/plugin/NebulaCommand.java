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
            case "diag" -> handleDiag(sender, args);
            case "settled" -> handleSettled(sender);
            case "be-settled" -> handleBlockEntitySettled(sender, args);
            case "be-furnace-timer" -> handleFurnaceTimer(sender, args);
            case "be-furnace-phase" -> handleFurnacePhase(sender, args);
            case "be-dropper-slot" -> handleDropperSlot(sender, args);
            case "be-dropper-phase" -> handleDropperPhase(sender, args);
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
            sender.sendMessage("§cUsage: /nebula capture <start|stop> [ticks] [--drive <seed>] [--period <n>]");
            return;
        }

        String action = args[1].toLowerCase();
        switch (action) {
            case "start" -> {
                // Parse `[ticks] [--drive <seed>] [--period <n>]` with the pure
                // parser so the flag grammar is unit-testable without a live plugin.
                CaptureStartArgs parsed = parseCaptureStart(args);
                if (parsed.error() != null) {
                    sender.sendMessage("§c" + parsed.error());
                    return;
                }
                plugin.startCapture(parsed.ticks(), parsed.driveSeed(), parsed.period());
                if (parsed.driveSeed() != null) {
                    sender.sendMessage("§aDriven capture started for " + parsed.ticks()
                        + " ticks (seed=" + parsed.driveSeed() + ", period=" + parsed.period() + ", "
                        + plugin.toggleSourceCount() + " toggle source(s))");
                    LOG.info("Driven capture started by " + sender.getName() + " for " + parsed.ticks()
                        + " ticks, seed=" + parsed.driveSeed() + ", period=" + parsed.period());
                } else {
                    sender.sendMessage("§aCapture started for " + parsed.ticks() + " ticks");
                    LOG.info("Capture started by " + sender.getName() + " for " + parsed.ticks() + " ticks");
                }
            }
            case "stop" -> {
                var frames = plugin.stopCapture();
                sender.sendMessage("§aCapture stopped: " + frames.size() + " frames recorded");

                // Summarise the recorded hashes so zero-diff behaviour is
                // observable: how many DISTINCT state hashes appeared, plus the
                // first/last for eyeballing.  NOTE: RedstoneCasStateHasher folds
                // the tick number into every hash, so even a perfectly static
                // world yields one distinct hash PER TICK (== frame count) — this
                // count is NOT the zero-diff signal.  Zero-diff is proven by
                // comparing two independent capture FILES (tickNumber resets to 0
                // each run): identical files ⇒ deterministic. See
                // scripts/zerodiff-harness.sh.
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

    /**
     * The parsed result of {@code /nebula capture start [ticks] [--drive <seed>]
     * [--period <n>]}. Exactly one of {@link #error} (a user-facing message, no
     * colour code) or a valid set of fields is meaningful: when {@code error} is
     * non-null the other fields must be ignored.
     */
    record CaptureStartArgs(long ticks, Long driveSeed, int period, String error) {
        static CaptureStartArgs ok(long ticks, Long driveSeed, int period) {
            return new CaptureStartArgs(ticks, driveSeed, period, null);
        }
        static CaptureStartArgs fail(String error) {
            return new CaptureStartArgs(0, null, 0, error);
        }
    }

    /**
     * Pure parser for {@code capture start} arguments (index 0 = "capture", 1 =
     * "start"). Kept static and free of Bukkit/plugin state so the flag grammar —
     * default ticks, {@code --drive <seed>}, {@code --period <n>}, and every error
     * path — is unit-testable without a running server. Absent {@code --drive}
     * yields a null seed (a static capture, byte-identical to the legacy path).
     */
    static CaptureStartArgs parseCaptureStart(String[] args) {
        long ticks = 1000; // default
        if (args.length >= 3) {
            try {
                ticks = Long.parseLong(args[2]);
            } catch (NumberFormatException e) {
                return CaptureStartArgs.fail("Invalid tick count: " + args[2]);
            }
        }
        Long driveSeed = null;
        int period = NebulaPlugin.DEFAULT_DRIVE_PERIOD;
        for (int i = 3; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("--drive")) {
                if (i + 1 >= args.length) {
                    return CaptureStartArgs.fail("--drive requires a seed value");
                }
                try {
                    driveSeed = Long.parseLong(args[++i]);
                } catch (NumberFormatException e) {
                    return CaptureStartArgs.fail("Invalid drive seed: " + args[i]);
                }
            } else if (args[i].equalsIgnoreCase("--period")) {
                if (i + 1 >= args.length) {
                    return CaptureStartArgs.fail("--period requires a tick count");
                }
                try {
                    period = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    return CaptureStartArgs.fail("Invalid period: " + args[i]);
                }
                if (period < 1) {
                    return CaptureStartArgs.fail("period must be >= 1");
                }
            } else {
                return CaptureStartArgs.fail("Unknown capture option: " + args[i]);
            }
        }
        return CaptureStartArgs.ok(ticks, driveSeed, period);
    }

    private static String shortHash(String hex) {
        return hex.length() <= 16 ? hex : hex.substring(0, 16) + "…";
    }

    private void handleStatus(CommandSender sender) {
        sender.sendMessage("§6Nebula Status:");
        sender.sendMessage("  §7Registered redstone components: §f" + plugin.componentCount());
        sender.sendMessage("  §7Toggle sources (levers/buttons): §f" + plugin.toggleSourceCount());
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
            plugin.microStepRecorder().reset();
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

        // DG1 Criterion 2: microsteps per tick must stay ≤ MAX_MICRO_STEPS (256).
        // The scheduler throws if a single tick exceeds the cap mid-run; this line
        // reports the observed distribution so the bound can be confirmed at scale.
        var m = plugin.microStepRecorder().snapshot();
        final int MAX_MICRO_STEPS = org.nebula.redstone.MicroStepScheduler.MAX_MICRO_STEPS;
        sender.sendMessage("§6Nebula microsteps per tick (DG1 Criterion 2, bound "
            + MAX_MICRO_STEPS + "):");
        sender.sendMessage(String.format(
            "  §7avg §f%.2f  §7min §f%d  §7max §f%d  §7p50 §f%d  §7p95 §f%d  §7p99 §f%d",
            m.avg(), m.min(), m.max(), m.p50(), m.p95(), m.p99()));
        if (m.max() > MAX_MICRO_STEPS) {
            sender.sendMessage(String.format(
                "  §cFAIL: max microsteps %d exceeds bound %d (DG1 Criterion 2 violated).",
                m.max(), MAX_MICRO_STEPS));
        } else {
            sender.sendMessage(String.format(
                "  §aPASS: max microsteps %d ≤ %d (DG1 Criterion 2 holds so far).",
                m.max(), MAX_MICRO_STEPS));
        }
    }

    /**
     * DG1 Criterion 2 caveat probe (redstone) + B8 C3 transfer probe (block-entity).
     * Toggles a per-invocation cascade diagnostic. On the redstone path
     * ({@code executeOwnedDag}) it logs each DAG tick's seed-task count and whether each
     * seed was already "settled" by Folia before the shadow ran (CAS power == synced NMS
     * power) as {@code CASCADE-DIAG:} lines. On the block-entity path
     * ({@code executeOwnedBlockEntityDag}) it logs each hopper/furnace task's live CAS
     * cooldown + self/neighbour slot deltas as {@code BE-CAS-DIAG:} lines — sample these
     * across consecutive ticks to catch a mid-cycle item transfer. Read both in
     * server-run.log. INFO-level and per-tick, so leave it OFF during perf measurement.
     */
    private void handleDiag(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nebula.status")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§eCascade diagnostic is "
                + (plugin.cascadeDiag() ? "§aON" : "§7OFF")
                + "§e. Usage: /nebula diag <on|off>");
            return;
        }
        String action = args[1].toLowerCase();
        switch (action) {
            case "on" -> {
                plugin.setCascadeDiag(true);
                sender.sendMessage("§aCascade diagnostic ON — toggle redstone (CASCADE-DIAG) or "
                    + "run a hopper (BE-CAS-DIAG), then read the lines in server-run.log. "
                    + "Turn OFF before /nebula perf.");
                LOG.info("Cascade diagnostic enabled by " + sender.getName());
            }
            case "off" -> {
                plugin.setCascadeDiag(false);
                sender.sendMessage("§aCascade diagnostic OFF.");
                LOG.info("Cascade diagnostic disabled by " + sender.getName());
            }
            default -> sender.sendMessage("§cUsage: /nebula diag <on|off>");
        }
    }

    /**
     * DG3 correctness: emit a settled-state SETTLED-DIAG snapshot. Snapshots every
     * tracked position on its owning region thread, logging {@code nebula=X folia=Y}
     * per position, so {@code SettledDivergenceGraderCli} can grade Folia-vs-Nebula
     * divergence at quiescence — the load-bearing signal the residual dirty rate is
     * not. Drive a toggle, let the circuit settle (watch for sustained
     * {@code microsteps=0} / no DAG ticks), THEN run this so the snapshot captures the
     * settled ON wires that never re-seed into the CASCADE-DIAG stream.
     */
    private void handleSettled(CommandSender sender) {
        if (!sender.hasPermission("nebula.status")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return;
        }
        int dispatched = plugin.emitSettledSnapshot();
        sender.sendMessage("§aSETTLED-DIAG snapshot dispatched for " + dispatched
            + " tracked position(s). Read the SETTLED-DIAG line(s) in server-run.log, "
            + "then grade with SettledDivergenceGraderCli.");
        LOG.info("Settled snapshot requested by " + sender.getName()
            + " (" + dispatched + " positions dispatched)");
    }

    /**
     * B8 C3 correctness: emit a settled-state {@code BE-SETTLED} snapshot for the
     * tracked block entities. Snapshots each ticking block entity on its owning region
     * thread, logging {@code nebula=X folia=Y} inventory counts per position, so
     * {@code BlockEntitySettledGraderCli} can grade Folia-vs-Nebula divergence at
     * quiescence — the block-entity twin of {@code /nebula settled}. Drive a hopper
     * transfer (summon an item over a hopper), let it settle (watch for the item feed
     * stopping and the hopper's self-slots stabilising), THEN run this so the snapshot
     * captures the resting inventory count, not a mid-cooldown sample.
     *
     * <p>Optional test-only argument {@code fault <offset>}: perturbs every emitted
     * {@code nebula=} count by {@code <offset>} so the shadow diverges from Folia by
     * construction. This is how the gate's TEETH are proven live — a nonzero offset must
     * drive {@code BlockEntitySettledGraderCli} to a graded FAIL. Omit it (the default,
     * offset 0) for an honest measurement.
     */
    private void handleBlockEntitySettled(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nebula.status")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return;
        }
        int faultOffset = 0;
        for (int i = 1; i < args.length - 1; i++) {
            if (args[i].equalsIgnoreCase("fault")) {
                try {
                    faultOffset = Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException e) {
                    sender.sendMessage("§cUsage: /nebula be-settled [fault <offset>] — offset must be an integer");
                    return;
                }
            }
        }
        int dispatched = plugin.emitBlockEntitySettledSnapshot(faultOffset);
        if (faultOffset != 0) {
            sender.sendMessage("§6FAULT INJECTION: nebula= counts offset by " + faultOffset
                + " — this run is a gate-teeth test and MUST grade FAIL.");
        }
        sender.sendMessage("§aBE-SETTLED snapshot dispatched for " + dispatched
            + " tracked block-entity position(s). Read the BE-SETTLED line(s) in "
            + "server-run.log, then grade with BlockEntitySettledGraderCli.");
        LOG.info("Block-entity settled snapshot requested by " + sender.getName()
            + " (" + dispatched + " positions dispatched, faultOffset=" + faultOffset + ")");
    }

    /**
     * B8 C3 correctness: emit a {@code BE-FURNACE-TIMER} gap snapshot for the tracked
     * furnaces. Snapshots each ticking furnace on its owning region thread, logging
     * {@code nebulaFuel/foliaFuel} and {@code nebulaCook/foliaCook} per position, so
     * {@code FurnaceTimerGapGraderCli} can grade the observe-only shadow's timer gap
     * against Folia's authoritative timers. Unlike {@code /nebula be-settled} (which
     * grades a settled inventory count for equality), this grades a GAP magnitude —
     * furnace timers never settle, so a resting-equality check does not apply. Place a
     * lit furnace with a raw input + fuel so it smelts autonomously (the cook-tick
     * seeder keeps it DAG-ticked), THEN run this while it is mid-smelt to measure the
     * live timer gap. A gap of ~0 is the precondition any furnace-timer write-back must
     * meet before it is armed.
     *
     * <p>With an optional {@code <count>} argument, fires a once-per-tick <em>burst</em> of
     * that many snapshots ({@code /nebula be-furnace-timer 200}) instead of a single shot.
     * A single shot almost never lands mid-cook — {@code cook_progress} climbs 0..200 and
     * resets each smelt, so a sparse sample overwhelmingly catches the resting 0. The burst
     * straddles the climb, giving the grader a real nonzero-{@code cook} sample.
     */
    private void handleFurnaceTimer(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nebula.status")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return;
        }
        if (args.length >= 2) {
            int samples;
            try {
                samples = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cUsage: /nebula be-furnace-timer [count] — count must be an integer");
                return;
            }
            int scheduled = plugin.emitFurnaceTimerBurst(samples);
            sender.sendMessage("§aBE-FURNACE-TIMER burst scheduled: " + scheduled
                + " once-per-tick snapshot(s) to catch cook_progress mid-climb. Read the "
                + "BE-FURNACE-TIMER lines in server-run.log, then grade with FurnaceTimerGapGraderCli.");
            LOG.info("Furnace-timer burst requested by " + sender.getName()
                + " (" + scheduled + " samples scheduled)");
            return;
        }
        int dispatched = plugin.emitFurnaceTimerSnapshot();
        sender.sendMessage("§aBE-FURNACE-TIMER snapshot dispatched for " + dispatched
            + " tracked furnace position(s). Read the BE-FURNACE-TIMER line(s) in "
            + "server-run.log, then grade the gap with FurnaceTimerGapGraderCli.");
        LOG.info("Furnace-timer snapshot requested by " + sender.getName()
            + " (" + dispatched + " positions dispatched)");
    }

    /**
     * {@code /nebula be-furnace-phase [count]} — arms the furnace-timer PHASE probe for
     * {@code count} game ticks (default 200 ≈ one cook cycle). While armed, each ticking
     * furnace's DAG pass emits a {@code BE-FURNACE-PHASE} line carrying Folia's authoritative
     * timers, the CAS timers just after {@code syncFromNms} (pre-action) and just after the
     * furnace action (post-action). This is the diagnostic the write-back decision needs: it
     * classifies the {@code BE-FURNACE-TIMER} {@code +1}/{@code -1} offset as an
     * ORDERING-ARTIFACT (pre==folia — the shadow tracks Folia at rate 1:1, the {@code +1} is
     * the action's own step) or a RATE-DIVERGENCE (pre≠folia — leave the timers to Folia).
     * Unlike {@code be-furnace-timer}, this rides the DAG's own tick, so it must run a WINDOW
     * of ticks over a furnace that is actively being DAG-ticked (place a lit hopper-fed furnace
     * and wait for the "FIRST cook-tick furnace re-seed" line first). Grade with
     * {@code FurnacePhaseGraderCli}.
     */
    private void handleFurnacePhase(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nebula.status")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return;
        }
        int window = 200;
        if (args.length >= 2) {
            try {
                window = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cUsage: /nebula be-furnace-phase [count] — count must be an integer");
                return;
            }
        }
        int scheduled = plugin.runFurnacePhaseProbe(window);
        sender.sendMessage("§aBE-FURNACE-PHASE probe armed for " + scheduled
            + " tick(s). Each ticking furnace's DAG pass emits folia/pre-action/post-action "
            + "timers. Read the BE-FURNACE-PHASE lines in server-run.log, then classify the "
            + "+1/-1 offset with FurnacePhaseGraderCli.");
        LOG.info("Furnace-timer phase probe requested by " + sender.getName()
            + " (" + scheduled + "-tick window armed)");
    }

    /**
     * {@code /nebula be-dropper-slot [count]} — emits a {@code BE-DROPPER-SLOT} gap snapshot
     * comparing Nebula's shadow CAS summed self-inventory count against Folia's authoritative
     * count for every tracked dropper/dispenser. This is the eject twin of
     * {@code be-furnace-timer}: it grades a GAP magnitude, not a settled equality, because a
     * pulsed dropper's self count steps down each eject rather than resting. Load a powered,
     * item-filled dropper (hopper-fed so it keeps ejecting) THEN run this to measure the live
     * eject gap. A gap of ~0 is the precondition any dropper eject write-back must meet before
     * it is armed (the double-ejector trap).
     *
     * <p>With an optional {@code <count>} argument, fires a once-per-tick <em>burst</em> of
     * that many snapshots ({@code /nebula be-dropper-slot 200}) instead of a single shot. A
     * single shot rarely straddles an eject step — the self count is flat between pulses — so
     * the burst is what brackets the {@code -1}/pulse cadence for the grader.
     */
    private void handleDropperSlot(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nebula.status")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return;
        }
        if (args.length >= 2) {
            int samples;
            try {
                samples = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cUsage: /nebula be-dropper-slot [count] — count must be an integer");
                return;
            }
            int scheduled = plugin.emitDropperSlotBurst(samples);
            sender.sendMessage("§aBE-DROPPER-SLOT burst scheduled: " + scheduled
                + " once-per-tick snapshot(s) to straddle the eject steps. Read the "
                + "BE-DROPPER-SLOT lines in server-run.log, then grade with DropperSlotGapGraderCli.");
            LOG.info("Dropper-slot burst requested by " + sender.getName()
                + " (" + scheduled + " samples scheduled)");
            return;
        }
        int dispatched = plugin.emitDropperSlotSnapshot();
        sender.sendMessage("§aBE-DROPPER-SLOT snapshot dispatched for " + dispatched
            + " tracked dropper/dispenser position(s). Read the BE-DROPPER-SLOT line(s) in "
            + "server-run.log, then grade the gap with DropperSlotGapGraderCli.");
        LOG.info("Dropper-slot snapshot requested by " + sender.getName()
            + " (" + dispatched + " positions dispatched)");
    }

    /**
     * {@code /nebula be-dropper-phase [count]} — arms the dropper/dispenser eject PHASE probe
     * for {@code count} game ticks (default 200). While armed, each ticking dropper/dispenser's
     * DAG pass emits a {@code BE-DROPPER-PHASE} line carrying Folia's authoritative summed self
     * count, the CAS self count just after {@code syncFromNms} (pre-action) and just after the
     * eject action (post-action). This is the diagnostic the dropper eject write-back decision
     * needs: it classifies the {@code BE-DROPPER-SLOT} {@code +1} offset (36ac531) as an
     * ORDERING-ARTIFACT (pre==folia — the shadow tracks Folia at rate 1:1, the {@code +1} is the
     * action's own eject step) or a RATE-DIVERGENCE (pre≠folia — the double-ejector signature,
     * leave the dropper to Folia). The dropper twin of {@code be-furnace-phase}: unlike
     * {@code be-dropper-slot}, this rides the DAG's own tick, so it must run a WINDOW of ticks
     * over a dropper that is actively being DAG-ticked (place a powered, hopper-fed dropper so it
     * keeps ejecting). Grade with {@code DropperPhaseGraderCli}.
     */
    private void handleDropperPhase(CommandSender sender, String[] args) {
        if (!sender.hasPermission("nebula.status")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return;
        }
        int window = 200;
        if (args.length >= 2) {
            try {
                window = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                sender.sendMessage("§cUsage: /nebula be-dropper-phase [count] — count must be an integer");
                return;
            }
        }
        int scheduled = plugin.runDropperPhaseProbe(window);
        sender.sendMessage("§aBE-DROPPER-PHASE probe armed for " + scheduled
            + " tick(s). Each ticking dropper/dispenser's DAG pass emits folia/pre-action/"
            + "post-action self counts. Read the BE-DROPPER-PHASE lines in server-run.log, then "
            + "classify the +1 offset with DropperPhaseGraderCli.");
        LOG.info("Dropper eject phase probe requested by " + sender.getName()
            + " (" + scheduled + "-tick window armed)");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6Nebula Commands:");
        sender.sendMessage("  §e/nebula capture start [ticks] [--drive <seed>] [--period <n>] §7- Start state capture (--drive = live-load driven)");
        sender.sendMessage("  §e/nebula capture stop §7- Stop capture and report");
        sender.sendMessage("  §e/nebula scan §7- Rescan loaded chunks for redstone components");
        sender.sendMessage("  §e/nebula status §7- Show plugin status");
        sender.sendMessage("  §e/nebula perf [reset] §7- Show DAG tick timing percentiles");
        sender.sendMessage("  §e/nebula diag <on|off> §7- Toggle per-tick cascade diagnostic (DG1 C2 probe)");
        sender.sendMessage("  §e/nebula settled §7- Emit a settled-state SETTLED-DIAG snapshot (DG3 divergence)");
        sender.sendMessage("  §e/nebula be-settled §7- Emit a settled-state BE-SETTLED snapshot (block-entity divergence)");
        sender.sendMessage("  §e/nebula be-furnace-timer [count] §7- Emit BE-FURNACE-TIMER gap snapshot(s); [count] = once-per-tick burst to catch cook mid-climb");
        sender.sendMessage("  §e/nebula be-furnace-phase [count] §7- Arm the BE-FURNACE-PHASE probe for [count] ticks; classifies the +1/-1 offset (ordering vs rate)");
        sender.sendMessage("  §e/nebula be-dropper-slot [count] §7- Emit BE-DROPPER-SLOT eject-gap snapshot(s); [count] = once-per-tick burst to straddle the eject steps");
        sender.sendMessage("  §e/nebula be-dropper-phase [count] §7- Arm the BE-DROPPER-PHASE probe for [count] ticks; classifies the +1 offset (ordering vs rate)");
        sender.sendMessage("  §e/nebula help §7- Show this help");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender,
                                      Command cmd,
                                      String alias,
                                      String[] args) {
        if (args.length == 1) {
            return Arrays.asList("capture", "status", "scan", "perf", "diag", "settled", "be-settled", "be-furnace-timer", "be-furnace-phase", "be-dropper-slot", "be-dropper-phase", "help");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("capture")) {
            return Arrays.asList("start", "stop");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("perf")) {
            return List.of("reset");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("diag")) {
            return Arrays.asList("on", "off");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("be-settled")) {
            return List.of("fault");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("be-settled")
                && args[1].equalsIgnoreCase("fault")) {
            return Arrays.asList("1", "-1", "64");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("capture") && args[1].equalsIgnoreCase("start")) {
            return Arrays.asList("100", "1000", "5000", "10000");
        }
        if (args.length >= 4 && args[0].equalsIgnoreCase("capture") && args[1].equalsIgnoreCase("start")) {
            String prev = args[args.length - 2];
            if (prev.equalsIgnoreCase("--drive")) return List.of("1", "42", "12345");
            if (prev.equalsIgnoreCase("--period")) return Arrays.asList("4", "8", "16");
            return Arrays.asList("--drive", "--period");
        }
        return List.of();
    }
}