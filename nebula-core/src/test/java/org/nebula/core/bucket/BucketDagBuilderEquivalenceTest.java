package org.nebula.core.bucket;

import org.junit.jupiter.api.Test;
import org.nebula.core.rw.RWSet;
import org.nebula.core.scheduler.DagBuilder;
import org.nebula.core.scheduler.DependencyEdge;
import org.nebula.core.scheduler.TaskGraph;
import org.nebula.core.scheduler.TaskNode;
import org.nebula.core.state.WorldPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Equivalence tests: the bucketed DAG builder must produce the same task set
 * and edge set as the O(N²) reference builder, regardless of bucket size.
 *
 * <p>This is the safety net for tuning bucket size per subsystem. Redstone
 * uses 16 (signal range 15+1); physics may use larger. The builder must stay
 * correct across all sizes.
 */
class BucketDagBuilderEquivalenceTest {

    private static TaskNode wireTask(String id, int x, int z) {
        // Models a redstone wire: reads 6 neighbours, writes self.
        WorldPos self = new WorldPos(0, x, 64, z);
        RWSet rw = RWSet.builder()
            .readBlock(new WorldPos(0, x - 1, 64, z))
            .readBlock(new WorldPos(0, x + 1, 64, z))
            .readBlock(new WorldPos(0, x, 64, z - 1))
            .readBlock(new WorldPos(0, x, 64, z + 1))
            .readBlock(new WorldPos(0, x, 63, z))
            .readBlock(new WorldPos(0, x, 65, z))
            .writeBlock(self)
            .build();
        return TaskNode.inert(id, "wire", rw);
    }

    private static List<String> sortedTaskIds(TaskGraph g) {
        return g.tasks().keySet().stream().sorted().toList();
    }

    private static List<DependencyEdge> sortedEdges(TaskGraph g) {
        return g.edges().stream()
            .sorted((a, b) -> {
                int s = a.sourceTaskId().compareTo(b.sourceTaskId());
                if (s != 0) return s;
                int t = a.targetTaskId().compareTo(b.targetTaskId());
                if (t != 0) return t;
                return a.type().compareTo(b.type());
            })
            .toList();
    }

    @Test
    void redstoneTunedBucketMatchesQuadraticBuilder() {
        // Generate a 8×8 grid of wires straddling several 16-block buckets.
        List<TaskNode> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                int x = i * 4;        // covers x = 0..28
                int z = j * 4;        // covers z = 0..28
                tasks.add(wireTask("W_" + i + "_" + j, x, z));
            }
        }

        TaskGraph quadratic = DagBuilder.build(tasks);
        TaskGraph bucketed = new BucketDagBuilder(16).build(tasks);

        assertEquals(sortedTaskIds(quadratic), sortedTaskIds(bucketed),
            "Bucketed builder must produce the same task set as the quadratic reference");
        assertEquals(sortedEdges(quadratic), sortedEdges(bucketed),
            "Bucketed builder must produce the same edge set as the quadratic reference");
    }

    @Test
    void bucketSizesAllAgreeWithQuadratic() {
        // Same workload built with 4 different bucket sizes — all must match the reference.
        List<TaskNode> tasks = new ArrayList<>();
        Random rng = new Random(0xC0DECAFEL);
        for (int i = 0; i < 40; i++) {
            int x = rng.nextInt(80);
            int z = rng.nextInt(80);
            tasks.add(wireTask("R" + i, x, z));
        }

        TaskGraph reference = DagBuilder.build(tasks);
        List<DependencyEdge> ref = sortedEdges(reference);
        List<String> refIds = sortedTaskIds(reference);

        for (int size : new int[]{4, 8, 16, 32, 64}) {
            TaskGraph g = new BucketDagBuilder(size).build(tasks);
            assertEquals(refIds, sortedTaskIds(g),
                "size=" + size + " task set diverged from reference");
            assertEquals(ref, sortedEdges(g),
                "size=" + size + " edge set diverged from reference");
        }
    }

    @Test
    void wireRwSetSpansAtMostTwoBucketsPerAxisAtSize16() {
        // Architectural assertion: at bucket size 16 (= redstone signal range + 1),
        // a single wire's RW-set never covers more than 2 distinct bucket coordinates
        // along any single axis (x, y, or z). This is what keeps per-bucket conflict
        // detection cheap. Worst case: 2×2×2 = 8 buckets.
        for (int x : new int[]{0, 7, 15, 16, 31, 100, -5}) {
            for (int z : new int[]{0, 15, 16, 47}) {
                TaskNode t = wireTask("T", x, z);
                var buckets = SpatialBucketIndex.bucketsFor(t, 16);
                long distinctBx = buckets.stream().map(BucketId::bx).distinct().count();
                long distinctBy = buckets.stream().map(BucketId::by).distinct().count();
                long distinctBz = buckets.stream().map(BucketId::bz).distinct().count();
                assertTrue(distinctBx <= 2,
                    "wire at (" + x + "," + z + ") spans " + distinctBx + " bucket-x at size 16");
                assertTrue(distinctBy <= 2,
                    "wire at (" + x + "," + z + ") spans " + distinctBy + " bucket-y at size 16");
                assertTrue(distinctBz <= 2,
                    "wire at (" + x + "," + z + ") spans " + distinctBz + " bucket-z at size 16");
                assertTrue(buckets.size() <= 8,
                    "wire at (" + x + "," + z + ") landed in " + buckets.size() + " buckets at size 16 (worst case 8)");
            }
        }
    }

    @Test
    void disjointBucketsEliminateInterBucketWork() {
        // Two wires 200 blocks apart at bucket size 16 — completely disjoint buckets.
        // The bucketed builder should recognise no conflict; the quadratic builder
        // also recognises this (positions don't overlap), so the graphs match.
        TaskNode a = wireTask("A", 0, 0);
        TaskNode b = wireTask("B", 200, 200);

        TaskGraph quadratic = DagBuilder.build(List.of(a, b));
        TaskGraph bucketed = new BucketDagBuilder(16).build(List.of(a, b));

        assertTrue(quadratic.edges().isEmpty(), "quadratic should see no conflict");
        assertTrue(bucketed.edges().isEmpty(), "bucketed should see no conflict");
    }
}
