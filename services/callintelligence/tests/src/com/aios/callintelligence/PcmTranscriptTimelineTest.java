package com.aios.callintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PcmTranscriptTimelineTest {
    private static final long PCM_BYTES_PER_SECOND = 16_000L * 2L;

    @Test
    public void initialStreamUsesCaptureOrigin() {
        PcmTranscriptTimeline timeline = new PcmTranscriptTimeline();
        Object stream = new Object();
        assertTrue(timeline.activate(stream, 0L));

        PcmTranscriptTimeline.Span span = timeline.map(stream, 500L, 2_000L);

        assertEquals(500L, span.startMillis);
        assertEquals(2_000L, span.endMillis);
    }

    @Test
    public void replacementStartsAtItsExactCapturedPcmOffset() {
        PcmTranscriptTimeline timeline = new PcmTranscriptTimeline();
        Object first = new Object();
        Object replacement = new Object();
        timeline.activate(first, 0L);
        timeline.deactivate(first);
        assertTrue(timeline.activate(replacement, 10L * PCM_BYTES_PER_SECOND));

        PcmTranscriptTimeline.Span span = timeline.map(replacement, 0L, 2_000L);

        assertEquals(10_000L, span.startMillis);
        assertEquals(12_000L, span.endMillis);
    }

    @Test
    public void cumulativePartialAndFinalMayReuseTheSameStartAndEnd() {
        PcmTranscriptTimeline timeline = new PcmTranscriptTimeline();
        Object stream = new Object();
        timeline.activate(stream, PCM_BYTES_PER_SECOND);

        PcmTranscriptTimeline.Span partial = timeline.map(stream, 0L, 2_000L);
        PcmTranscriptTimeline.Span revised = timeline.map(stream, 0L, 4_000L);
        PcmTranscriptTimeline.Span finished = timeline.map(stream, 0L, 4_000L);

        assertEquals(1_000L, partial.startMillis);
        assertEquals(3_000L, partial.endMillis);
        assertEquals(5_000L, revised.endMillis);
        assertEquals(revised.endMillis, finished.endMillis);
    }

    @Test
    public void detachedAndOlderIdentitiesCannotMapTimestamps() {
        PcmTranscriptTimeline timeline = new PcmTranscriptTimeline();
        Object first = new Object();
        Object replacement = new Object();
        timeline.activate(first, 0L);
        assertTrue(timeline.deactivate(first));
        assertNull(timeline.map(first, 0L, 1L));
        timeline.activate(replacement, PCM_BYTES_PER_SECOND);

        assertNull(timeline.map(first, 0L, 1L));
        assertFalse(timeline.deactivate(first));
    }

    @Test
    public void malformedOffsetsAndSourceRangesFailClosed() {
        PcmTranscriptTimeline timeline = new PcmTranscriptTimeline();
        Object stream = new Object();

        assertFalse(timeline.activate(null, 0L));
        assertFalse(timeline.activate(stream, -1L));
        assertTrue(timeline.activate(stream, 1L));
        assertNull(timeline.map(stream, -1L, 1L));
        assertNull(timeline.map(stream, 2L, 1L));
    }
}
