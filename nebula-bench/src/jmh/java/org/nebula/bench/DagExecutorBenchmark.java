package org.nebula.bench;

import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.*;
import org.nebula.core.state.WorldPos;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.*;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class DagExecutorBenchmark {

    @Param({"4", "8"})
    private int coreCount;

    @Param({"100", "500"})
    private int taskCount;

    private TaskGraph graph;
    private WorkStealingExecutor executor;

    @Setup(Level.Trial)
    public void setup() {
        Random rng = new Random(123);
        Map<String, TaskNode> taskMap = new LinkedHashMap<>();
        Set<DependencyEdge> edges = new HashSet<>();

        for (int i = 0; i < taskCount; i++) {
            int x = rng.nextInt(128);
            int z = rng.nextInt(128);
            RWSet rwSet = RWSet.builder()
                .readBlock(new WorldPos(0, x, 64, z))
                .writeBlock(new WorldPos(0, x, 65, z))
                .build();
            taskMap.put("t-" + i, new TaskNode("t-" + i, "entity", rwSet, () -> {
                Blackhole.consumeCPU(50);
            }));
        }

        for (int i = 1; i < taskCount; i++) {
            if (rng.nextDouble() < 0.15) {
                int dep = rng.nextInt(i);
                edges.add(new DependencyEdge("t-" + dep, "t-" + i, DependencyType.RAW));
            }
        }

        graph = new TaskGraph(taskMap, edges);
        executor = new WorkStealingExecutor(coreCount);
    }

    @TearDown(Level.Trial)
    public void teardown() {
        if (executor != null) executor.close();
    }

    @Benchmark
    public void executeGraphWorkStealing(Blackhole bh) throws DagExecutionException {
        DagExecutionReport report = DagExecutor.execute(graph, TaskRunner.DIRECT, executor);
        bh.consume(report);
    }

    @Benchmark
    public void executeGraphDirect(Blackhole bh) throws DagExecutionException {
        DagExecutionReport report = DagExecutor.execute(graph, TaskRunner.DIRECT);
        bh.consume(report);
    }
}
