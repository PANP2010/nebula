package org.nebula.bench;

import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.scheduler.TaskRunner;
import org.nebula.core.state.WorldPos;
import org.nebula.guard.RWGuard;
import org.nebula.guard.RWGuardConfig;
import org.nebula.guard.RWGuardMode;
import org.nebula.guard.RWGuardTaskRunner;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * RW-Guard tracing overhead — DIRECT runner vs RWGuardTaskRunner (WARN mode).
 *
 * <p>Measures per-task cost of the access tracing instrumentation that wraps
 * each task body in a ThreadLocal trace context, ferrying read/write events
 * to {@link RWGuard#executeTaskWithTracing}.  Because the instrumented body
 * itself is a no-op, all observed cost is the guard's bookkeeping —
 * {@code AccessTrace} push/pop, set diffing, and exit-time consistency check.
 *
 * <p>The task RWSets are built large enough that the consistency checker
 * has real work to do (16 written blocks per task) but small enough that
 * the per-task allocation pattern is realistic for blockEntity ticks.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class RWGuardBenchmark {

    @Param({"4", "16", "64"})
    private int writeFootprint;

    private TaskNode task;
    private TaskRunner direct;
    private TaskRunner guarded;

    @Setup(Level.Trial)
    public void setup() {
        Random rng = new Random(123);
        RWSet.Builder b = RWSet.builder();
        for (int i = 0; i < writeFootprint; i++) {
            b.writeBlock(new WorldPos(0, rng.nextInt(1 << 20), 64, rng.nextInt(1 << 20)));
        }
        RWSet rwSet = b.build();
        task = new TaskNode("guard_bench", "BENCH", rwSet, () -> Blackhole.consumeCPU(50));

        direct = TaskRunner.DIRECT;
        // Enable WARN tracing with no on-disk log (null violationLog disables writes).
        RWGuard.configure(RWGuardConfig.enabled(RWGuardMode.WARN).withViolationLog(null));
        guarded = new RWGuardTaskRunner(0L);
    }

    @TearDown(Level.Trial)
    public void teardown() {
        RWGuard.configure(new RWGuardConfig(false, 0.0, RWGuardMode.WARN, null, 100, false));
    }

    @Benchmark
    public void runDirect(Blackhole bh) throws Exception {
        direct.run(task);
        bh.consume(task);
    }

    @Benchmark
    public void runGuarded(Blackhole bh) throws Exception {
        guarded.run(task);
        bh.consume(task);
    }
}
