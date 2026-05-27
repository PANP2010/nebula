package org.nebula.core.vap;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.scheduler.TaskRunner;
import org.nebula.core.scheduler.TickPipeline;
import org.nebula.core.state.GlobalKey;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * VAP Level 0 plugin compatibility harness (architecture §13.2, §14.4 Month 7-8).
 *
 * <p>Validates the Level 0 contract end-to-end via TickPipeline + PluginTaskQueue
 * using synthetic "plugins" that exhibit realistic API call patterns:
 *
 * <ul>
 *   <li>Multiple plugins submit tasks concurrently during the kernel phase
 *   <li>Plugin phase runs after all kernel tasks complete
 *   <li>Inter-plugin order = registration order
 *   <li>Intra-plugin order = submission order
 *   <li>Plugin task failures are isolated (do not abort the kernel phase)
 * </ul>
 *
 * <p>Real third-party plugin JARs (EssentialsX, WorldGuard, LuckPerms,
 * PlaceholderAPI) are required for the §14.4 DG3 acceptance criterion
 * (≥80% Level 0 compat rate). This harness verifies the runtime contract
 * those plugins will rely on.
 */
class VapLevel0CompatHarnessTest {

    @Test
    void kernelPhaseCompletesBeforePluginPhase() throws Exception {
        List<String> phaseLog = Collections.synchronizedList(new ArrayList<>());

        PluginTaskQueue pq = new PluginTaskQueue(List.of("Essentials"));
        TickPipeline pipeline = new TickPipeline(
            completed -> List.of(),
            TaskRunner.DIRECT,
            256,
            pq
        );

        // Kernel task — simulates a vanilla tick action; while running, a "plugin"
        // submits a follow-up task as it would when reacting to e.g. EntityMoveEvent.
        TaskNode kernelTask = new TaskNode(
            "kernel-move",
            "ENTITY_MOVE",
            RWSet.builder().writeBlock(new WorldPos(0, 0, 64, 0)).build(),
            () -> {
                phaseLog.add("kernel:move");
                pq.submit(new PluginTask("Essentials", "broadcast",
                    () -> phaseLog.add("plugin:Essentials:broadcast")));
            }
        );

        pipeline.execute(List.of(kernelTask));

        // Order must be: every kernel task done, then plugin phase drains
        assertEquals(List.of("kernel:move", "plugin:Essentials:broadcast"), phaseLog);
    }

    @Test
    void interPluginOrderIsRegistrationOrder() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        PluginTaskQueue pq = new PluginTaskQueue(List.of("WorldGuard", "LuckPerms", "PlaceholderAPI"));
        TickPipeline pipeline = new TickPipeline(
            completed -> List.of(), TaskRunner.DIRECT, 256, pq
        );

        // Kernel task fans out to 3 different plugins; intentionally submit in
        // reverse-registration order to verify the queue re-orders by registration.
        TaskNode kernel = new TaskNode(
            "kernel-trigger",
            "TRIGGER",
            RWSet.builder().writeGlobal(GlobalKey.ALL).build(),
            () -> {
                pq.submit(new PluginTask("PlaceholderAPI", "expand", () -> log.add("PAPI")));
                pq.submit(new PluginTask("LuckPerms", "check", () -> log.add("LP")));
                pq.submit(new PluginTask("WorldGuard", "guard", () -> log.add("WG")));
            }
        );
        pipeline.execute(List.of(kernel));

        assertEquals(List.of("WG", "LP", "PAPI"), log);
    }

    @Test
    void intraPluginPreservesSubmissionOrder() throws Exception {
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        PluginTaskQueue pq = new PluginTaskQueue(List.of("ChatPlugin"));
        TickPipeline pipeline = new TickPipeline(
            completed -> List.of(), TaskRunner.DIRECT, 256, pq
        );

        TaskNode kernel = new TaskNode(
            "kernel",
            "KERNEL",
            RWSet.builder().writeGlobal(GlobalKey.ALL).build(),
            () -> {
                for (int i = 0; i < 5; i++) {
                    final int seq = i;
                    pq.submit(new PluginTask("ChatPlugin", "msg-" + seq,
                        () -> log.add("msg-" + seq)));
                }
            }
        );
        pipeline.execute(List.of(kernel));

        assertEquals(List.of("msg-0", "msg-1", "msg-2", "msg-3", "msg-4"), log);
    }

    @Test
    void multiplePluginsSubmitFromMultipleKernelTasks() throws Exception {
        AtomicInteger pluginExecutions = new AtomicInteger();
        List<String> order = Collections.synchronizedList(new ArrayList<>());

        PluginTaskQueue pq = new PluginTaskQueue(List.of("PluginA", "PluginB"));
        TickPipeline pipeline = new TickPipeline(
            completed -> List.of(), TaskRunner.DIRECT, 256, pq
        );

        // 3 independent kernel tasks (different write positions = no conflicts)
        // each spawns one task per plugin.
        List<TaskNode> kernelTasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            final int kernelIdx = i;
            kernelTasks.add(new TaskNode(
                "kernel-" + i,
                "KERNEL",
                RWSet.builder().writeBlock(new WorldPos(0, kernelIdx, 64, 0)).build(),
                () -> {
                    pq.submit(new PluginTask("PluginB", "B-from-" + kernelIdx,
                        () -> { order.add("B" + kernelIdx); pluginExecutions.incrementAndGet(); }));
                    pq.submit(new PluginTask("PluginA", "A-from-" + kernelIdx,
                        () -> { order.add("A" + kernelIdx); pluginExecutions.incrementAndGet(); }));
                }
            ));
        }
        pipeline.execute(kernelTasks);

        // 6 total plugin tasks; PluginA's all execute before any PluginB
        assertEquals(6, pluginExecutions.get());
        // First three entries belong to PluginA (registration index 0)
        for (int i = 0; i < 3; i++) {
            assertTrue(order.get(i).startsWith("A"),
                "Expected PluginA task at index " + i + ", got " + order.get(i));
        }
        for (int i = 3; i < 6; i++) {
            assertTrue(order.get(i).startsWith("B"),
                "Expected PluginB task at index " + i + ", got " + order.get(i));
        }
    }

    @Test
    void pluginTaskFailureSurfacesAsPluginTaskException() {
        PluginTaskQueue pq = new PluginTaskQueue(List.of("BrokenPlugin"));
        TickPipeline pipeline = new TickPipeline(
            completed -> List.of(), TaskRunner.DIRECT, 256, pq
        );

        TaskNode kernel = new TaskNode(
            "kernel",
            "KERNEL",
            RWSet.builder().writeGlobal(GlobalKey.ALL).build(),
            () -> pq.submit(new PluginTask("BrokenPlugin", "boom", () -> {
                throw new RuntimeException("synthetic plugin failure");
            }))
        );

        // Pipeline execute should propagate the plugin failure (the kernel phase
        // already completed; the failure happens in the plugin phase). Per §13.2
        // a failure in the plugin phase is reported but does not corrupt kernel
        // state, since kernel is already done.
        Exception thrown = assertThrows(Exception.class, () -> pipeline.execute(List.of(kernel)));
        // The PluginTaskException should appear somewhere in the chain
        Throwable cur = thrown;
        boolean foundPluginException = false;
        while (cur != null) {
            if (cur instanceof PluginTaskException) { foundPluginException = true; break; }
            cur = cur.getCause();
        }
        assertTrue(foundPluginException,
            "Expected PluginTaskException in cause chain, got: " + thrown);
    }

    @Test
    void unknownPluginsExecuteAfterRegisteredOnes() throws Exception {
        // Per §13.2: plugins not in the registration list still execute, but at
        // the end of the plugin phase. This is the conservative behaviour for
        // dynamically-loaded or unrecognised plugins.
        List<String> log = Collections.synchronizedList(new ArrayList<>());
        PluginTaskQueue pq = new PluginTaskQueue(List.of("Known"));
        TickPipeline pipeline = new TickPipeline(
            completed -> List.of(), TaskRunner.DIRECT, 256, pq
        );

        TaskNode kernel = new TaskNode(
            "kernel",
            "KERNEL",
            RWSet.builder().writeGlobal(GlobalKey.ALL).build(),
            () -> {
                pq.submit(new PluginTask("Unknown", "u1", () -> log.add("U")));
                pq.submit(new PluginTask("Known", "k1", () -> log.add("K")));
            }
        );
        pipeline.execute(List.of(kernel));

        assertEquals(List.of("K", "U"), log);
    }
}
