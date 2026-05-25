package org.nebula.bench;

import org.nebula.core.vap.MvccVersionStore;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class MvccStoreBenchmark {

    private MvccVersionStore<Integer> store;
    private MvccVersionStore<Integer> mergeStore;

    @Setup(Level.Iteration)
    public void setup() {
        store = new MvccVersionStore<>(0);
        store.snapshot();
        mergeStore = new MvccVersionStore<>(0, Integer::sum);
        mergeStore.snapshot();
    }

    @Benchmark
    public void snapshotRead(Blackhole bh) {
        bh.consume(store.read());
    }

    @Benchmark
    public void latestRead(Blackhole bh) {
        bh.consume(store.readLatest());
    }

    @Benchmark
    public void casUpdate(Blackhole bh) {
        bh.consume(store.update(v -> v + 1));
    }

    @Benchmark
    public void mergeOperation(Blackhole bh) {
        bh.consume(mergeStore.merge(10, 20));
    }

    @Benchmark
    public void snapshotCycle(Blackhole bh) {
        store.snapshot();
        store.update(v -> v + 1);
        store.update(v -> v + 1);
        bh.consume(store.read());
        bh.consume(store.readLatest());
    }

    @Benchmark
    @Threads(8)
    public void concurrentCasUpdate(Blackhole bh) {
        bh.consume(store.update(v -> v + 1));
    }
}
