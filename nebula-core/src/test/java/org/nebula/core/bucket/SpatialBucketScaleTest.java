package org.nebula.core.bucket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nebula.core.bucket.BucketDagBuilder;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.DependencyEdge;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.scheduler.TaskGraph;
import org.nebula.core.state.EntityField;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spatial-bucketing scale tests (arch doc §4.1, §4.3).
 *
 * <p>Verifies that {@link SpatialBucketIndex} and {@link BucketDagBuilder}
 * degrade gracefully on large workloads — the property the P1 brief calls
 * "spatial bucketing at scale". Three checks:
 * <ol>
 *   <li>Indexing a 5k task workload produces a non-trivial number of
 *       buckets and spreads the load across them (no single bucket holds a
 *       disproportionate share).</li>
 *   <li>{@link BucketDagBuilder#build} completes on the same workload and
 *       returns a non-empty, internally consistent graph.</li>
 *   <li>The "self-only entity write" fast path is taken on a hot entity
 *       workload — the indexer skips the O(K²) conflict-detection loop and
 *       the {@link BucketDagBuilder#fastPathHits()} counter increments.</li>
 * </ol>
 */
class SpatialBucketScaleTest {

    private static final int DIM_OVERWORLD = 0;

    @BeforeEach
    void resetFastPathCounters() {
        BucketDagBuilder.resetFastPathCounters();
    }

    @Test
    void spatialIndexPartitionsLargeWorkloadIntoBoundedBuckets() {
        // 5,000 self-only-entity-write tasks spread across a 64×64 horizontal
        // area. With the default 32-block bucket size we expect at most
        // 2×1×2=4 buckets (y=64 is one bucket) — the assertion is that
        // load spread is bounded and GLOBAL is not used.
        List<TaskNode> tasks = syntheticEntityLoad(5_000, 64, 0xC0FFEEL);
        SpatialBucketIndex index = new SpatialBucketIndex(tasks);

        Set<BucketId> bucketIds = index.bucketIds();
        assertFalse(bucketIds.contains(SpatialBucketIndex.GLOBAL_BUCKET),
            "self-only entity tasks should not fall into the GLOBAL bucket");
        assertTrue(bucketIds.size() > 1,
            "expected multiple buckets for a 64×64 footprint, got " + bucketIds.size());

        int maxPerBucket = 0;
        for (BucketId bid : bucketIds) {
            int n = index.tasksInBucket(bid).size();
            if (n > maxPerBucket) maxPerBucket = n;
        }
        // The spread should be reasonable: no single bucket holds more than
        // half the load (a 5000-task load evenly spread over ≥ 4 buckets
        // is < 1250 per bucket; 50% is a loose sanity cap).
        assertTrue(maxPerBucket < tasks.size() / 2,
            "one bucket held " + maxPerBucket + " tasks — bucketing did not spread the load");
    }

    @Test
    void bucketDagBuilderCompletesLargeWorkloadAndHitsFastPath() {
        List<TaskNode> tasks = syntheticEntityLoad(5_000, 64, 0xCAFEBABEL);
        BucketDagBuilder builder = new BucketDagBuilder();
        long t0 = System.nanoTime();
        TaskGraph graph = builder.build(tasks);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertNotNull(graph);
        assertEquals(tasks.size(), graph.tasks().size(),
            "all input tasks must appear in the output graph");

        // Every edge's endpoints must be present in the graph.
        for (DependencyEdge e : graph.edges()) {
            assertNotNull(graph.tasks().get(e.sourceTaskId()),
                "edge source missing: " + e.sourceTaskId());
            assertNotNull(graph.tasks().get(e.targetTaskId()),
                "edge target missing: " + e.targetTaskId());
        }

        // The "self-only" fast path must be hit on the vast majority of
        // buckets. Every bucket whose tasks are all self-only entity writes
        // contributes one fast-path hit. We expect at least one hit.
        long hits = BucketDagBuilder.fastPathHits();
        assertTrue(hits > 0, "expected fast-path hits on a self-only workload");

        // Sanity bound: a 5k self-only task build should be fast.
        assertTrue(elapsedMs < 10_000,
            "build took " + elapsedMs + "ms on a 5k workload — budget exceeded");
    }

    @Test
    void sparseRedstoneLayoutMapsToPerChunkBuckets() {
        // Simulate a redstone workload: 200 unique redstone tasks spread
        // over a 256×256 horizontal area. We expect up to 8×8=64 buckets.
        List<TaskNode> tasks = new ArrayList<>();
        Random r = new Random(7L);
        for (int i = 0; i < 200; i++) {
            int x = r.nextInt(256);
            int z = r.nextInt(256);
            int y = 64;
            WorldPos pos = new WorldPos(DIM_OVERWORLD, x, y, z);
            // Redstone dust: writes its own block and reads it back.
            RWSet rw = RWSet.builder()
                .readBlock(pos)
                .writeBlock(pos)
                .build();
            tasks.add(new TaskNode("rs-" + i, "redstone", rw, () -> {}));
        }
        SpatialBucketIndex index = new SpatialBucketIndex(tasks);
        Set<BucketId> bucketIds = index.bucketIds();
        // The 32-block bucket over a 256x256 footprint gives at most 8x8 = 64
        // spatial buckets. We expect at least a handful.
        assertTrue(bucketIds.size() > 4 && bucketIds.size() <= 64,
            "expected 4–64 buckets for a sparse 200-task redstone layout, got "
                + bucketIds.size());
    }

    @Test
    void selfOnlyFlagDrivesFastPathRecognition() {
        // Sanity: a self-only entity task must report true through the
        // RWSet so the indexer can short-circuit it.
        RWSet selfOnly = RWSet.builder()
            .readEntity(new EntityField(1, "position"))
            .writeEntity(new EntityField(1, "position"))
            .build();
        assertTrue(selfOnly.isSelfOnlyEntityWrite(),
            "entity-only RWSet must report isSelfOnlyEntityWrite=true");

        RWSet withBlockWrite = RWSet.builder()
            .readEntity(new EntityField(1, "position"))
            .writeEntity(new EntityField(1, "position"))
            .writeBlock(new WorldPos(DIM_OVERWORLD, 0, 64, 0))
            .build();
        assertFalse(withBlockWrite.isSelfOnlyEntityWrite(),
            "block-write must disqualify self-only fast path");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Generates {@code n} self-only-entity-write tasks uniformly distributed
     * over a {@code span × 64 × span} region centred on the origin. The
     * returned tasks are deterministic for a given {@code seed}.
     */
    private static List<TaskNode> syntheticEntityLoad(int n, int span, long seed) {
        List<TaskNode> out = new ArrayList<>(n);
        Random r = new Random(seed);
        for (int i = 0; i < n; i++) {
            int x = r.nextInt(span);
            int z = r.nextInt(span);
            int y = 64;
            // The block read is what makes the indexer put this task in a
            // real (non-GLOBAL) bucket; the entity field write/read is what
            // makes the fast path fire.
            WorldPos pos = new WorldPos(DIM_OVERWORLD, x, y, z);
            RWSet rw = RWSet.builder()
                .readBlock(pos)
                .readEntity(new EntityField(i, "position"))
                .writeEntity(new EntityField(i, "position"))
                .build();
            out.add(new TaskNode("e" + i, "entity-move", rw, () -> {}));
        }
        return out;
    }
}
