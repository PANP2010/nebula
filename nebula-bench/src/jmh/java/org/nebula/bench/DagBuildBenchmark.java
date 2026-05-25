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
public class DagBuildBenchmark {

    @Param({"50", "200", "500"})
    private int taskCount;

    private List<TaskNode> tasks;
    private Set<DependencyEdge> edges;

    @Setup(Level.Trial)
    public void setup() {
        Random rng = new Random(42);
        tasks = new ArrayList<>();
        edges = new HashSet<>();

        for (int i = 0; i < taskCount; i++) {
            int x = rng.nextInt(64);
            int z = rng.nextInt(64);
            RWSet rwSet = RWSet.builder()
                .readBlock(new WorldPos(0, x, 64, z))
                .writeBlock(new WorldPos(0, x, 65, z))
                .build();
            tasks.add(new TaskNode("task-" + i, "redstone", rwSet, () -> {}));
        }

        for (int i = 1; i < taskCount; i++) {
            if (rng.nextDouble() < 0.3) {
                int dep = rng.nextInt(i);
                edges.add(new DependencyEdge("task-" + dep, "task-" + i, DependencyType.RAW));
            }
        }
    }

    @Benchmark
    public void buildTaskGraph(Blackhole bh) {
        Map<String, TaskNode> taskMap = new LinkedHashMap<>();
        for (TaskNode t : tasks) taskMap.put(t.taskId(), t);
        TaskGraph graph = new TaskGraph(taskMap, edges);
        bh.consume(graph);
    }

    @Benchmark
    public void computeTopologicalLayers(Blackhole bh) {
        Map<String, TaskNode> taskMap = new LinkedHashMap<>();
        for (TaskNode t : tasks) taskMap.put(t.taskId(), t);
        TaskGraph graph = new TaskGraph(taskMap, edges);
        bh.consume(graph.topologicalLayers());
    }

    @Benchmark
    public void computeScc(Blackhole bh) {
        Map<String, TaskNode> taskMap = new LinkedHashMap<>();
        for (TaskNode t : tasks) taskMap.put(t.taskId(), t);
        TaskGraph graph = new TaskGraph(taskMap, edges);
        bh.consume(graph.stronglyConnectedComponents());
    }

    @Benchmark
    public void rwConflictDetection(Blackhole bh) {
        for (int i = 0; i < tasks.size() - 1; i++) {
            bh.consume(tasks.get(i).declaredRWSet().hasWriteReadConflictWith(
                tasks.get(i + 1).declaredRWSet()));
        }
    }
}
