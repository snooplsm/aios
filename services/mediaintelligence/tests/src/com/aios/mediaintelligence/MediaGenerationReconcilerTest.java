package com.aios.mediaintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

public final class MediaGenerationReconcilerTest {
    @Test
    public void completeScanAdvancesPastObservedProviderGeneration() {
        MediaGenerationReconciler.Plan plan = MediaGenerationReconciler.plan(
                point(10L, MediaGenerationReconciler.END_OF_GENERATION),
                20L,
                List.of(row(12L, 2L, false, true)),
                false);

        assertEquals(1, plan.ready.size());
        assertPoint(20L, MediaGenerationReconciler.END_OF_GENERATION, plan.next);
        assertFalse(plan.more);
        assertFalse(plan.blockedByPendingItem);
    }

    @Test
    public void pendingInsertCannotBeSkipped() {
        MediaGenerationReconciler.Plan plan = MediaGenerationReconciler.plan(
                point(10L, MediaGenerationReconciler.END_OF_GENERATION),
                30L,
                List.of(
                        row(12L, 2L, false, true),
                        row(15L, 5L, true, false),
                        row(18L, 8L, false, true)),
                false);

        assertEquals(List.of("content://media/2"),
                plan.ready.stream().map(value -> value.uri).toList());
        assertPoint(12L, 2L, plan.next);
        assertTrue(plan.blockedByPendingItem);
        assertFalse(plan.more);
    }

    @Test
    public void truncatedBatchResumesWithinSharedGeneration() {
        MediaGenerationReconciler.Plan plan = MediaGenerationReconciler.plan(
                point(24L, 7L),
                100L,
                List.of(row(25L, 8L, false, true), row(25L, 9L, false, true)),
                true);

        assertPoint(25L, 9L, plan.next);
        assertTrue(plan.more);
    }

    @Test
    public void invalidFinishedRowsAdvanceButAreNotQueued() {
        MediaGenerationReconciler.Plan plan = MediaGenerationReconciler.plan(
                point(5L, MediaGenerationReconciler.END_OF_GENERATION),
                9L,
                List.of(row(8L, 8L, false, false)),
                false);

        assertTrue(plan.ready.isEmpty());
        assertPoint(9L, MediaGenerationReconciler.END_OF_GENERATION, plan.next);
    }

    @Test
    public void rowsAtOrBeforeCompositeCursorAreIgnored() {
        MediaGenerationReconciler.Plan plan = MediaGenerationReconciler.plan(
                point(25L, 8L),
                25L,
                List.of(row(25L, 7L, false, true), row(25L, 9L, false, true)),
                true);

        assertEquals(List.of("content://media/9"),
                plan.ready.stream().map(value -> value.uri).toList());
        assertPoint(25L, 9L, plan.next);
    }

    @Test
    public void generationRegressionFailsClosed() {
        try {
            MediaGenerationReconciler.plan(point(10L, 0L), 9L, List.of(), false);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static MediaGenerationReconciler.CursorPoint point(long generation, long mediaId) {
        return new MediaGenerationReconciler.CursorPoint(generation, mediaId);
    }

    private static MediaGenerationReconciler.Row row(
            long generation, long mediaId, boolean pending, boolean eligible) {
        return new MediaGenerationReconciler.Row(
                mediaId,
                "content://media/" + mediaId,
                generation,
                generation + 1L,
                "image/jpeg",
                1_000L,
                pending,
                eligible);
    }

    private static void assertPoint(
            long generation,
            long mediaId,
            MediaGenerationReconciler.CursorPoint actual) {
        assertEquals(generation, actual.generation);
        assertEquals(mediaId, actual.mediaId);
    }
}
