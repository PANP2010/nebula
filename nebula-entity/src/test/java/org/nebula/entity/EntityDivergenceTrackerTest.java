package org.nebula.entity;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the observe-only entity divergence signal. These pin the
 * frame-for-frame contiguity contract and the drift math without any live
 * server — the pure prerequisite for wiring the signal into the entity tick.
 */
class EntityDivergenceTrackerTest {

    private static final double EPS = 1e-9;

    @Test
    void firstSightingEmitsNoSample() {
        EntityDivergenceTracker tracker = new EntityDivergenceTracker();
        Optional<EntityDivergenceTracker.Sample> s =
            tracker.record(1L, 10L, new Vec3(0, 100, 0), new Vec3(0, 99.92, 0));
        assertTrue(s.isEmpty(), "no prior prediction to diff against on first sighting");
        assertEquals(0, tracker.sampleCount());
        assertEquals(1, tracker.trackedEntities());
    }

    @Test
    void contiguousTicksEmitFrameForFrameDrift() {
        EntityDivergenceTracker tracker = new EntityDivergenceTracker();
        // Tick 10: authoritative pos (0,100,0), predict next = (0,99.92,0).
        tracker.record(1L, 10L, new Vec3(0, 100, 0), new Vec3(0, 99.92, 0));
        // Tick 11: Folia's actual pos came out at (0,99.90,0) — the model over-fell by 0.02.
        Optional<EntityDivergenceTracker.Sample> s =
            tracker.record(1L, 11L, new Vec3(0, 99.90, 0), new Vec3(0, 99.80, 0));

        assertTrue(s.isPresent(), "contiguous tick 10→11 must produce a sample");
        EntityDivergenceTracker.Sample sample = s.get();
        assertEquals(1L, sample.entityId());
        assertEquals(11L, sample.tick());
        assertEquals(new Vec3(0, 99.92, 0), sample.predicted());
        assertEquals(new Vec3(0, 99.90, 0), sample.authoritative());
        assertEquals(0.02, sample.drift(), EPS, "|99.92 - 99.90| = 0.02");
        assertEquals(1, tracker.sampleCount());
    }

    @Test
    void nonContiguousTickGapSkipsSample() {
        EntityDivergenceTracker tracker = new EntityDivergenceTracker();
        tracker.record(1L, 10L, new Vec3(0, 100, 0), new Vec3(0, 99.92, 0));
        // Entity stood still for a while; next move seen at tick 25, not 11.
        Optional<EntityDivergenceTracker.Sample> s =
            tracker.record(1L, 25L, new Vec3(0, 99.92, 0), new Vec3(0, 99.84, 0));
        assertTrue(s.isEmpty(), "a 15-tick gap is not a frame-for-frame pair");
        assertEquals(0, tracker.sampleCount());

        // But the fresh prediction is now stored, so tick 26 does produce a sample.
        Optional<EntityDivergenceTracker.Sample> s2 =
            tracker.record(1L, 26L, new Vec3(0, 99.85, 0), new Vec3(0, 99.77, 0));
        assertTrue(s2.isPresent(), "26 is contiguous with the re-stored 25");
        assertEquals(0.01, s2.get().drift(), EPS);
    }

    @Test
    void driftIsEuclideanAcrossAllThreeAxes() {
        EntityDivergenceTracker tracker = new EntityDivergenceTracker();
        tracker.record(7L, 0L, new Vec3(0, 0, 0), new Vec3(3, 0, 0));
        // predicted (3,0,0) vs authoritative (0,4,0): sqrt(3^2 + 4^2) = 5.
        Optional<EntityDivergenceTracker.Sample> s =
            tracker.record(7L, 1L, new Vec3(0, 4, 0), new Vec3(0, 4, 0));
        assertTrue(s.isPresent());
        assertEquals(5.0, s.get().drift(), EPS, "3-4-5 across x and y");
    }

    @Test
    void perfectPredictionYieldsZeroDrift() {
        EntityDivergenceTracker tracker = new EntityDivergenceTracker();
        Vec3 predicted = new Vec3(1.5, 63.0, -2.5);
        tracker.record(1L, 5L, new Vec3(1, 64, -2), predicted);
        Optional<EntityDivergenceTracker.Sample> s =
            tracker.record(1L, 6L, predicted, new Vec3(1.5, 62.9, -2.5));
        assertTrue(s.isPresent());
        assertEquals(0.0, s.get().drift(), EPS, "model matched Folia exactly this frame");
    }

    @Test
    void tracksEntitiesIndependently() {
        EntityDivergenceTracker tracker = new EntityDivergenceTracker();
        tracker.record(1L, 10L, new Vec3(0, 100, 0), new Vec3(0, 99.9, 0));
        tracker.record(2L, 10L, new Vec3(5, 100, 5), new Vec3(5, 99.9, 5));
        assertEquals(2, tracker.trackedEntities());
        assertEquals(0, tracker.sampleCount(), "both are first sightings");

        Optional<EntityDivergenceTracker.Sample> s1 =
            tracker.record(1L, 11L, new Vec3(0, 99.9, 0), new Vec3(0, 99.8, 0));
        assertTrue(s1.isPresent());
        assertEquals(1L, s1.get().entityId());
        assertEquals(0.0, s1.get().drift(), EPS);
    }

    @Test
    void aggregatesMeanAndMaxDrift() {
        EntityDivergenceTracker tracker = new EntityDivergenceTracker();
        // Entity 1: predict (0,10,0)→actual differs by 0.10 next tick.
        tracker.record(1L, 0L, new Vec3(0, 0, 0), new Vec3(0, 10, 0));
        tracker.record(1L, 1L, new Vec3(0, 10.10, 0), new Vec3(0, 20, 0)); // drift 0.10
        // Entity 1 next: predict (0,20,0)→actual differs by 0.30.
        tracker.record(1L, 2L, new Vec3(0, 20.30, 0), new Vec3(0, 30, 0)); // drift 0.30

        assertEquals(2, tracker.sampleCount());
        assertEquals(0.20, tracker.meanDrift(), EPS, "(0.10 + 0.30) / 2");
        assertEquals(0.30, tracker.maxDrift(), EPS);
        assertEquals(1L, tracker.maxDriftEntityId());
        assertEquals(2L, tracker.maxDriftTick());
    }

    @Test
    void emptyTrackerReportsZeroes() {
        EntityDivergenceTracker tracker = new EntityDivergenceTracker();
        assertEquals(0, tracker.sampleCount());
        assertEquals(0.0, tracker.meanDrift(), EPS);
        assertEquals(0.0, tracker.maxDrift(), EPS);
        assertFalse(tracker.summary().isEmpty());
        assertTrue(tracker.summary().contains("samples=0"));
    }

    @Test
    void countsEveryObservationWhetherOrNotItSamples() {
        EntityDivergenceTracker tracker = new EntityDivergenceTracker();
        tracker.record(1L, 10L, new Vec3(0, 100, 0), new Vec3(0, 99.9, 0)); // first sighting
        tracker.record(1L, 11L, new Vec3(0, 99.9, 0), new Vec3(0, 99.8, 0)); // contiguous sample
        assertEquals(2, tracker.observationCount(),
            "both records are observations, even the first sighting that yields no sample");
        assertEquals(1, tracker.sampleCount());
    }

    @Test
    void nonContiguousSkipsAreCountedNotSilentlyDropped() {
        // The live drain cadence advances the tick by 2 per entity DAG tick (begin/end
        // alternation), so a naive game-tick key produces gap=2 pairs that never sample.
        // A silent tracker would then read as "never ran" — indistinguishable from a
        // dead pipeline. nonContiguousSkips must make that observable.
        EntityDivergenceTracker tracker = new EntityDivergenceTracker();
        tracker.record(1L, 10L, new Vec3(0, 100, 0), new Vec3(0, 99.92, 0)); // first sighting
        Optional<EntityDivergenceTracker.Sample> s =
            tracker.record(1L, 12L, new Vec3(0, 99.84, 0), new Vec3(0, 99.76, 0)); // gap=2
        assertTrue(s.isEmpty(), "gap of 2 is not a frame-for-frame pair");
        assertEquals(0, tracker.sampleCount());
        assertEquals(1, tracker.nonContiguousSkips(), "the gap-2 pair is counted, not dropped");
        assertEquals(2, tracker.lastGap(), "lastGap exposes the observed cadence");
        assertTrue(tracker.summary().contains("nonContiguousSkips=1"));
        assertTrue(tracker.summary().contains("lastGap=2"));
    }
}
