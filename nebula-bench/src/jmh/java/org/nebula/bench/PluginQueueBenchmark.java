package org.nebula.bench;

import org.nebula.core.vap.*;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class PluginQueueBenchmark {

    @Param({"10", "50", "200"})
    private int taskCount;

    private PluginTaskQueue queue;

    @Setup(Level.Invocation)
    public void setup() {
        queue = new PluginTaskQueue(List.of("PluginA", "PluginB", "PluginC"));
        String[] plugins = {"PluginA", "PluginB", "PluginC"};
        for (int i = 0; i < taskCount; i++) {
            String plugin = plugins[i % 3];
            queue.submit(new PluginTask(plugin, "op-" + i, () -> Blackhole.consumeCPU(10), i % 5));
        }
    }

    @Benchmark
    public void drainAndExecute(Blackhole bh) throws PluginTaskException {
        bh.consume(queue.drainAndExecute());
    }

    @Benchmark
    public void submitOnly(Blackhole bh) {
        PluginTaskQueue q = new PluginTaskQueue(List.of("A"));
        for (int i = 0; i < taskCount; i++) {
            q.submit(new PluginTask("A", "op", () -> {}, 0));
        }
        bh.consume(q.pendingCount());
    }
}
