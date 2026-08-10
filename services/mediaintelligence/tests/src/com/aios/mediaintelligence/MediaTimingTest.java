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
                "image/jpeg",
                1_000L,
                1_250L,
                2_000L,
                700L,
                100L,
                400L,
                MediaTiming.UNKNOWN_MILLIS,
                MediaTiming.UNKNOWN_MILLIS);

        assertEquals(MediaTiming.KIND_PHOTO, sample.mediaKind);
        assertEquals(1_000L, sample.observedToIndexMillis);
        assertEquals(250L, sample.queueToStartMillis);
        assertEquals(700L, sample.processingMillis);
        assertEquals(100L, sample.inputPreparationMillis);
        assertEquals(400L, sample.modelRequestMillis);
        assertEquals(MediaTiming.UNKNOWN_MILLIS, sample.videoAudioDurationMillis);
    }

    @Test
    public void backwardsWallClockIsMarkedUnknownWithoutLosingElapsedTiming() {
        MediaTiming.Sample sample = MediaTiming.completed(
                "video/mp4", 2_000L, 1_900L, 1_950L, 40L, 10L, 20L, 5_000L, 10L);

        assertEquals(MediaTiming.KIND_VIDEO, sample.mediaKind);
        assertEquals(MediaTiming.UNKNOWN_MILLIS, sample.observedToIndexMillis);
        assertEquals(MediaTiming.UNKNOWN_MILLIS, sample.queueToStartMillis);
        assertEquals(40L, sample.processingMillis);
        assertEquals(5_000L, sample.videoAudioDurationMillis);
    }

    @Test
    public void invalidIntervalsAndTimingBreakdownFailClosed() {
        assertIllegalArgument(() -> MediaTiming.elapsedDuration(2L, 1L));
        assertIllegalArgument(() -> MediaTiming.completed(
                "audio/mp4", 1L, 2L, 3L, 2L, 1L, 1L, -1L, -1L));
        assertIllegalArgument(() -> new MediaTiming.Sample(
                MediaTiming.KIND_PHOTO, 10L, 2L, 5L, 3L, 3L, -1L, -1L, 20L));
        assertIllegalArgument(() -> new MediaTiming.Sample(
                MediaTiming.KIND_PHOTO, 10L, 2L, 8L, 1L, 3L, 1L, 1L, 20L));
        assertIllegalArgument(() -> new MediaTiming.Sample(
                MediaTiming.KIND_VIDEO, 10L, 2L, 8L, 1L, 3L, -1L, 0L, 20L));
        MediaTiming.Sample legacy = new MediaTiming.Sample(
                MediaTiming.KIND_VIDEO, 10L, 2L, 8L, 1L, 3L, -1L, -1L, 20L);
        assertEquals(MediaTiming.UNKNOWN_MILLIS, legacy.videoAudioDurationMillis);
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
                    MediaTiming.UNKNOWN_MILLIS,
                    MediaTiming.UNKNOWN_MILLIS,
                    10_000L + index));
        }
        List<MediaTiming.Sample> videos = List.of(
                new MediaTiming.Sample(
                        MediaTiming.KIND_VIDEO,
                        1_000L,
                        100L,
                        1_000L,
                        100L,
                        700L,
                        2_000L,
                        500L,
                        20_001L),
                new MediaTiming.Sample(
                        MediaTiming.KIND_VIDEO,
                        2_000L,
                        200L,
                        1_200L,
                        100L,
                        1_000L,
                        1_000L,
                        1_000L,
                        20_002L));
        MediaTimingSummary.Snapshot snapshot = MediaTimingSummary.snapshot(
                30_000L, photos, videos);

        assertEquals(20, snapshot.photos.sampleCount);
        assertEquals(Long.valueOf(1_000L), snapshot.photos.p50ObservedToIndexMillis);
        assertEquals(Long.valueOf(1_900L), snapshot.photos.p95ObservedToIndexMillis);
        assertEquals(Long.valueOf(500L), snapshot.photos.p50ProcessingMillis);
        assertEquals(Long.valueOf(950L), snapshot.photos.p95ProcessingMillis);
        assertNull(snapshot.photos.p95VideoAudioPipelineMillis);
        assertEquals(2, snapshot.videos.sampleCount);
        assertEquals(Long.valueOf(1_000L), snapshot.videos.p50VideoAudioDurationMillis);
        assertEquals(Long.valueOf(2_000L), snapshot.videos.p95VideoAudioDurationMillis);
        assertEquals(Long.valueOf(500L), snapshot.videos.p50VideoAudioPipelineMillis);
        assertEquals(0, snapshot.photos.videoAudioSampleCount);
        assertEquals(2, snapshot.videos.videoAudioSampleCount);
        assertEquals(2, snapshot.videos.videoAudioRealtimeFactorSampleCount);
        assertEquals(
                Long.valueOf(250L),
                snapshot.videos.p50VideoAudioRealtimeFactorPermille);
        assertEquals(
                Long.valueOf(1_000L),
                snapshot.videos.p95VideoAudioRealtimeFactorPermille);
        assertTrue(snapshot.toJson().startsWith(
                "{\"schema_version\":2,\"generated_at_epoch_ms\":30000," +
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
                    MediaTiming.KIND_PHOTO,
                    1L,
                    1L,
                    3L,
                    1L,
                    1L,
                    MediaTiming.UNKNOWN_MILLIS,
                    MediaTiming.UNKNOWN_MILLIS,
                    index + 1L));
        }
        assertIllegalArgument(() -> MediaTimingSummary.snapshot(1L, samples, List.of()));
    }

    @Test
    public void audioLessAndLegacyVideosHaveHonestEvidenceDenominators() {
        MediaTiming.Sample audioLess = new MediaTiming.Sample(
                MediaTiming.KIND_VIDEO, 100L, 10L, 100L, 10L, 80L, 0L, 20L, 1_000L);
        MediaTiming.Sample legacy = new MediaTiming.Sample(
                MediaTiming.KIND_VIDEO,
                100L,
                10L,
                100L,
                10L,
                80L,
                MediaTiming.UNKNOWN_MILLIS,
                MediaTiming.UNKNOWN_MILLIS,
                900L);

        MediaTimingSummary.Group group = MediaTimingSummary.snapshot(
                2_000L, List.of(), List.of(audioLess, legacy)).videos;

        assertEquals(2, group.sampleCount);
        assertEquals(1, group.videoAudioSampleCount);
        assertEquals(0, group.videoAudioRealtimeFactorSampleCount);
        assertEquals(Long.valueOf(0L), group.p50VideoAudioDurationMillis);
        assertEquals(Long.valueOf(20L), group.p50VideoAudioPipelineMillis);
        assertNull(group.p50VideoAudioRealtimeFactorPermille);
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
