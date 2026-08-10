package com.aios.mediaintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class VideoStoryboardPlanTest {
    @Test
    public void samplesTwentyChronologicalSegmentMidpoints() {
        long[] times = VideoStoryboardPlan.sampleTimesUs(10_000L);

        assertEquals(20, VideoStoryboardPlan.sampleCount());
        assertEquals(20, times.length);
        assertEquals(250_000L, times[0]);
        assertEquals(5_250_000L, times[10]);
        assertEquals(9_750_000L, times[19]);
    }

    @Test
    public void oddDurationSamplesStayOrderedAndInsideVideo() {
        long durationMillis = 123_456L;
        long[] times = VideoStoryboardPlan.sampleTimesUs(durationMillis);

        for (int index = 0; index < times.length; index++) {
            assertTrue(times[index] > 0L);
            assertTrue(times[index] < durationMillis * 1_000L);
            if (index > 0) assertTrue(times[index] > times[index - 1]);
        }
    }

    @Test
    public void scalingBoundsLandscapePortraitAndRotation() {
        VideoStoryboardPlan.Size landscape =
                VideoStoryboardPlan.scaledSize(1920, 1080, 0, 384);
        VideoStoryboardPlan.Size portrait =
                VideoStoryboardPlan.scaledSize(1080, 1920, 0, 384);
        VideoStoryboardPlan.Size rotated =
                VideoStoryboardPlan.scaledSize(1920, 1080, 90, 384);
        VideoStoryboardPlan.Size small =
                VideoStoryboardPlan.scaledSize(100, 50, 0, 384);

        assertEquals(384, landscape.width);
        assertEquals(216, landscape.height);
        assertEquals(216, portrait.width);
        assertEquals(384, portrait.height);
        assertEquals(216, rotated.width);
        assertEquals(384, rotated.height);
        assertEquals(100, small.width);
        assertEquals(50, small.height);
    }

    @Test
    public void invalidMetadataFailsClosed() {
        assertIllegalArgument(() -> VideoStoryboardPlan.sampleTimesUs(0L));
        assertIllegalArgument(() -> VideoStoryboardPlan.sampleTimesUs(Long.MAX_VALUE));
        assertIllegalArgument(() -> VideoStoryboardPlan.scaledSize(0, 1080, 0, 384));
        assertIllegalArgument(() -> VideoStoryboardPlan.scaledSize(1920, 1080, 45, 384));
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
