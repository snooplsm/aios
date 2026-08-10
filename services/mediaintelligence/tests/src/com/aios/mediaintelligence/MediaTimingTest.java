package com.aios.mediaintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class MediaTimingTest {
    @Test
    public void completedSampleSeparatesWallAndElapsedDurations() {
        MediaTiming.Sample sample = MediaTiming.completed(
                "image/jpeg", 1_000L, 1_250L, 2_000L, 700L, 100L, 400L);

        assertEquals(MediaTiming.KIND_PHOTO, sample.mediaKind);
        assertEquals(1_000L, sample.observedToIndexMillis);
        assertEquals(250L, sample.queueToStartMillis);
        assertEquals(700L, sample.processingMillis);
        assertEquals(100L, sample.inputPreparationMillis);
        assertEquals(400L, sample.modelRequestMillis);
    }

    @Test
    public void backwardsWallClockIsMarkedUnknownWithoutLosingElapsedTiming() {
        MediaTiming.Sample sample = MediaTiming.completed(
                "video/mp4", 2_000L, 1_900L, 1_950L, 40L, 10L, 20L);

        assertEquals(MediaTiming.KIND_VIDEO, sample.mediaKind);
        assertEquals(MediaTiming.UNKNOWN_MILLIS, sample.observedToIndexMillis);
        assertEquals(MediaTiming.UNKNOWN_MILLIS, sample.queueToStartMillis);
        assertEquals(40L, sample.processingMillis);
    }

    @Test
    public void invalidIntervalsAndTimingBreakdownFailClosed() {
        assertIllegalArgument(() -> MediaTiming.elapsedDuration(2L, 1L));
        assertIllegalArgument(() -> MediaTiming.completed(
                "audio/mp4", 1L, 2L, 3L, 2L, 1L, 1L));
        assertIllegalArgument(() -> new MediaTiming.Sample(
                MediaTiming.KIND_PHOTO, 10L, 2L, 5L, 3L, 3L, 20L));
    }

    @Test
    public void snapshotUsesBoundedNearestRankPercentilesAndNoMediaContent() {
        List<MediaTiming.Sample> photos = new ArrayList<>();
        for (int index = 1; index <= 20; index++) {
            photos.add(new MediaTiming.Sample(
                    MediaTiming.KIND_PHOTO,
                    index * 100L,
                    index * 10L,
                    index * 50L,
                    index * 5L,
                    index * 20L,
                    10_000L + index));
        }
        MediaTimingSummary.Snapshot snapshot = MediaTimingSummary.snapshot(
                20_000L, photos, List.of());

        assertEquals(20, snapshot.photos.sampleCount);
        assertEquals(Long.valueOf(1_000L), snapshot.photos.p50ObservedToIndexMillis);
        assertEquals(Long.valueOf(1_900L), snapshot.photos.p95ObservedToIndexMillis);
        assertEquals(Long.valueOf(500L), snapshot.photos.p50ProcessingMillis);
        assertEquals(Long.valueOf(950L), snapshot.photos.p95ProcessingMillis);
        assertEquals(0, snapshot.videos.sampleCount);
        assertNull(snapshot.videos.p95ModelRequestMillis);
        assertTrue(snapshot.toJson().startsWith(
                "{\"schema_version\":1,\"generated_at_epoch_ms\":20000," +
                        "\"max_samples_per_kind\":100,\"photos\":"));
        if (snapshot.toJson().contains("content://") || snapshot.toJson().contains("caption")) {
            fail("timing JSON exposed media content");
        }
    }

    @Test
    public void snapshotRejectsMoreThanBoundedHistory() {
        List<MediaTiming.Sample> samples = new ArrayList<>();
        for (int index = 0; index <= MediaTimingSummary.MAX_SAMPLES_PER_KIND; index++) {
            samples.add(new MediaTiming.Sample(
                    MediaTiming.KIND_PHOTO, 1L, 1L, 3L, 1L, 1L, index + 1L));
        }
        assertIllegalArgument(() -> MediaTimingSummary.snapshot(1L, samples, List.of()));
    }

    private static void assertIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
