package org.nebula.statusui;

import org.nebula.core.metrics.MicroStepRecorder;
import org.nebula.core.metrics.TickTimeRecorder;

import java.util.Random;

/**
 * Mock status provider that generates synthetic data for testing the UI.
 */
public final class MockStatusProvider implements StatusProvider {

    private final Random random = new Random();
    private long tickCount = 0;
    private int componentCount = 127;
    private int entityCount = 45;
    private int blockEntityCount = 23;

    @Override
    public TickTimeRecorder.Snapshot getTickTimeSnapshot() {
        tickCount++;
        double avg = 0.5 + random.nextDouble() * 2.5;
        return new TickTimeRecorder.Snapshot(
            tickCount, 512,
            (long) (0.3 * 1_000_000),
            (long) (4.0 * 1_000_000),
            avg * 1_000_000,
            (long) (avg * 0.9 * 1_000_000),
            (long) (avg * 1.5 * 1_000_000),
            (long) (avg * 2.0 * 1_000_000)
        );
    }

    @Override
    public MicroStepRecorder.Snapshot getMicroStepSnapshot() {
        int avg = 5 + random.nextInt(45);
        return new MicroStepRecorder.Snapshot(
            tickCount, 512,
            1, 128, avg,
            Math.max(1, avg - 3),
            avg + 10,
            Math.min(128, avg + 25)
        );
    }

    @Override
    public int getLastLayersExecuted() {
        return 3 + random.nextInt(8);
    }

    @Override
    public int getFidelityTier() {
        return 0;
    }

    @Override
    public int getComponentCount() {
        componentCount += random.nextInt(3) - 1;
        componentCount = Math.max(100, Math.min(200, componentCount));
        return componentCount;
    }

    @Override
    public int getToggleSourceCount() {
        return 12 + random.nextInt(5);
    }

    @Override
    public int getRedstoneStateSize() {
        return componentCount * 2;
    }

    @Override
    public int getEntityStateSize() {
        entityCount += random.nextInt(3) - 1;
        return entityCount;
    }

    @Override
    public int getBlockEntityStateSize() {
        blockEntityCount += random.nextInt(2);
        return blockEntityCount;
    }

    @Override
    public long getServerTick() {
        return tickCount;
    }

    @Override
    public int getOnlinePlayers() {
        return 0;
    }

    @Override
    public int getLoadedChunks() {
        return 256 + random.nextInt(64);
    }
}
