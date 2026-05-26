package org.nebula.bench;

import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.ParallelTaskRunner;
import org.nebula.core.scheduler.TaskGenerator;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.scheduler.TaskRunner;
import org.nebula.core.scheduler.TickPipeline;
import org.nebula.core.state.WorldPos;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end TickPipeline.execute() benchmark — serial DIRECT runner vs
 * intra-layer ParallelTaskRunner across N independent tasks per tick.
 *
 * <p>Tasks are intentionally non-conflicting (each writes a distinct
 * WorldPos) so the DAG produces a single layer of size N — what we want
 * is to measure layer-fanout overhead vs. parallel speedup as task work
 * grows. Synthetic per-task work is {@link Blackhole#consumeCPU} with
 * a configurable token count.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class TickPipelineBenchmark {

    @Param({"16", "64", "256"})
    private int taskCount;

    @Param({"50", "500"})
    private int workTokensPerTask;

    private List<TaskNode> tasks;
    private TickPipeline serialPipeline;
    private TickPipeline parallelPipeline;
    private ExecutorService parallelPool;

    @Setup(Level.Trial)
    public void setup() {
        Random rng = new Random(42);
        tasks = new ArrayList<>(taskCount);
        long tokens = workTokensPerTask;
        for (int i = 0; i < taskCount; i++) {
            int x = rng.nextInt(1 << 20);
            int z = rng.nextInt(1 << 20);
            // Fully disjoint write footprints → single non-conflicting layer.
            RWSet rwSet = RWSet.builder()
                .writeBlock(new WorldPos(0, x, 64, z))
                .build();
            tasks.add(new TaskNode("t" + i, "BENCH", rwSet, () -> Blackhole.consumeCPU(tokens)));
        }

        serialPipeline = new TickPipeline(NOOP_GENERATOR, TaskRunner.DIRECT, 1);

        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        parallelPool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "Bench-Worker");
            t.setDaemon(true);
            return t;
        });
        parallelPipeline = new TickPipeline(
            NOOP_GENERATOR,
            new ParallelTaskRunner(TaskRunner.DIRECT, parallelPool, 2),
            1
        );
    }

    private static final TaskGenerator NOOP_GENERATOR = task -> List.of();

    @TearDown(Level.Trial)
    public void teardown() {
        if (parallelPool != null) parallelPool.shutdownNow();
    }

    @Benchmark
    public void executeSerial(Blackhole bh) throws Exception {
        bh.consume(serialPipeline.execute(tasks));
    }

    @Benchmark
    public void executeParallel(Blackhole bh) throws Exception {
        bh.consume(parallelPipeline.execute(tasks));
    }
}
