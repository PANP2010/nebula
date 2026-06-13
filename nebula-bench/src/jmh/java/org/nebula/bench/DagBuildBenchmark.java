package org.nebula.bench;

import org.nebula.core.bucket.BucketDagBuilder;
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

    @Param({"independent", "redstone", "redstone_sparse"})
    private String workload;

    @Param({"16", "128"})
    private int bucketSize;

    private List<TaskNode> tasks;
    private Set<DependencyEdge> edges;

    @Setup(Level.Trial)
    public void setup() {
        Random rng = new Random(42);
        tasks = switch (workload) {
            case "independent" -> independentBlockTasks();
            case "redstone" -> redstoneWireTasks(1);
            case "redstone_sparse" -> redstoneWireTasks(8);
            default -> throw new IllegalArgumentException("Unknown workload: " + workload);
        };
        edges = new HashSet<>();

        for (int i = 1; i < taskCount; i++) {
            if (rng.nextDouble() < 0.3) {
                int dep = rng.nextInt(i);
                edges.add(new DependencyEdge("task-" + dep, "task-" + i, DependencyType.RAW));
            }
        }
    }

    private List<TaskNode> independentBlockTasks() {
        List<TaskNode> generated = new ArrayList<>(taskCount);
        for (int i = 0; i < taskCount; i++) {
            int x = i % 128;
            int z = i / 128;
            RWSet rwSet = RWSet.builder()
                .readBlock(new WorldPos(0, x, 64, z))
                .writeBlock(new WorldPos(0, x, 65, z))
                .build();
            generated.add(new TaskNode("task-" + i, "independent", rwSet, () -> {}));
        }
        return List.copyOf(generated);
    }

    /**
     * Synthetic wire grid. Spacing 1 intentionally creates adjacent neighbour
     * reads and large SCCs; larger spacing keeps wires disconnected for DAG
     * builder cost comparisons without oversized-SCC noise.
     */
    private List<TaskNode> redstoneWireTasks(int spacing) {
        List<TaskNode> generated = new ArrayList<>(taskCount);
        int side = (int) Math.ceil(Math.sqrt(taskCount));
        for (int i = 0; i < taskCount; i++) {
            int x = (i % side) * spacing;
            int z = (i / side) * spacing;
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

    @Benchmark
    public void buildTaskGraph(Blackhole bh) {
        Map<String, TaskNode> taskMap = new LinkedHashMap<>();
        for (TaskNode t : tasks) taskMap.put(t.taskId(), t);
        TaskGraph graph = new TaskGraph(taskMap, edges);
        bh.consume(graph);
    }

    @Benchmark
    public void buildWithQuadraticDagBuilder(Blackhole bh) {
        bh.consume(DagBuilder.build(tasks));
    }

    @Benchmark
    public void buildWithBucketDagBuilder(Blackhole bh) {
        bh.consume(new BucketDagBuilder(bucketSize).build(tasks));
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
