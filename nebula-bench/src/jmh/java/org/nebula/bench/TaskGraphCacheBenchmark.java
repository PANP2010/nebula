package org.nebula.bench;

import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.DagBuilder;
import org.nebula.core.scheduler.SccContractor;
import org.nebula.core.scheduler.TaskGraph;
import org.nebula.core.scheduler.TaskGraphCache;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Isolates the three cross-tick {@link TaskGraphCache} code paths so the
 * cost of the content-sensitive fingerprint can be weighed against the
 * build work it lets us skip.
 *
 * <p>Per the DG2 finding (docs/NEBULA_BLOCKERS.md): DAG <em>build</em> time
 * dominates MSPT (~8.6ms build vs ~1.7ms run), and the next perf lever is
 * decomposition-level caching. The cache's hardened fingerprint now hashes
 * concrete RWSet contents every tick — this benchmark answers whether that
 * fingerprint cost is small relative to the rebuild it avoids on a cache hit.
 *
 * <ul>
 *   <li>{@link #fingerprintOnly} — raw {@code fingerprint(tasks)} cost paid
 *       on every tick regardless of hit/miss.</li>
 *   <li>{@link #cacheHit} — primed cache: fingerprint + {@code tryHit}
 *       (the steady-state stable-workload path).</li>
 *   <li>{@link #cacheMissRebuild} — fingerprint + full DAG build +
 *       {@code put} (first tick, or whenever the workload changes).</li>
 * </ul>
 *
 * <p>The {@code entity} workload models the dominant production case:
 * self-only tasks (each reads/writes a distinct column) that produce zero
 * conflict edges, so the build is dominated by SCC/sort bookkeeping rather
 * than edge generation. The {@code redstone} workload adds neighbour reads
 * so the build does real conflict work.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class TaskGraphCacheBenchmark {

    @Param({"256", "2000", "7300"})
    private int taskCount;

    @Param({"entity", "redstone"})
    private String workload;

    private List<TaskNode> tasks;
    private TaskGraphCache primedCache;
    private long fingerprint;

    @Setup(Level.Trial)
    public void setup() {
        tasks = switch (workload) {
            case "entity" -> entityTasks();
            case "redstone" -> redstoneTasks();
            default -> throw new IllegalArgumentException("Unknown workload: " + workload);
        };
        fingerprint = TaskGraphCache.fingerprint(tasks);

        // Prime a cache so cacheHit measures the steady-state path.
        primedCache = new TaskGraphCache();
        TaskGraph fresh = freshBuild(tasks);
        primedCache.put(fingerprint, fresh.edges(), fresh.topologicalLayers());
    }

    /** Self-only entity-like tasks: distinct read/write column per task, zero edges. */
    private List<TaskNode> entityTasks() {
        List<TaskNode> generated = new ArrayList<>(taskCount);
        for (int i = 0; i < taskCount; i++) {
            int x = i % 4096;
            int z = i / 4096;
            RWSet rwSet = RWSet.builder()
                .readBlock(new WorldPos(0, x, 64, z))
                .writeBlock(new WorldPos(0, x, 64, z))
                .build();
            generated.add(new TaskNode("task-" + i, "entity", rwSet, () -> {}));
        }
        return List.copyOf(generated);
    }

    /** Sparse wire grid (spacing 8): real conflict work without oversized-SCC noise. */
    private List<TaskNode> redstoneTasks() {
        List<TaskNode> generated = new ArrayList<>(taskCount);
        int side = (int) Math.ceil(Math.sqrt(taskCount));
        for (int i = 0; i < taskCount; i++) {
            int x = (i % side) * 8;
            int z = (i / side) * 8;
            WorldPos self = new WorldPos(0, x, 64, z);
            RWSet rwSet = RWSet.builder()
                .readBlock(new WorldPos(0, x - 1, 64, z))
                .readBlock(new WorldPos(0, x + 1, 64, z))
                .readBlock(new WorldPos(0, x, 64, z - 1))
                .readBlock(new WorldPos(0, x, 64, z + 1))
                .readBlock(new WorldPos(0, x, 63, z))
                .readBlock(new WorldPos(0, x, 65, z))
                .writeBlock(self)
                .build();
            generated.add(new TaskNode("task-" + i, "redstone", rwSet, () -> {}));
        }
        return List.copyOf(generated);
    }

    /** Mirror of TickPipeline.buildInitialGraph's miss-path build choice. */
    private static TaskGraph freshBuild(List<TaskNode> tasks) {
        if (tasks.size() >= 64 && DagBuilder.isFastPathSafe(tasks)) {
            return DagBuilder.buildFast(tasks, new SccContractor());
        }
        return DagBuilder.build(tasks, new SccContractor());
    }

    @Benchmark
    public void fingerprintOnly(Blackhole bh) {
        bh.consume(TaskGraphCache.fingerprint(tasks));
    }

    @Benchmark
    public void cacheHit(Blackhole bh) {
        long fp = TaskGraphCache.fingerprint(tasks);
        bh.consume(primedCache.tryHit(tasks, fp));
    }

    @Benchmark
    public void cacheMissRebuild(Blackhole bh) {
        TaskGraphCache cache = new TaskGraphCache();
        long fp = TaskGraphCache.fingerprint(tasks);
        TaskGraph miss = cache.tryHit(tasks, fp);
        if (miss == null) {
            TaskGraph fresh = freshBuild(tasks);
            cache.put(fp, fresh.edges(), fresh.topologicalLayers());
            bh.consume(fresh);
        } else {
            bh.consume(miss);
        }
    }
}
