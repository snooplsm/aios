package com.aios.mediaintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

public final class VideoEmbeddedMetadataTest {
    private static final String VISION_DIGEST =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String AUDIO_DIGEST =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    public void encodesDescriptionAndTimedCueAndClearEvents() {
        VideoEmbeddedMetadata.Data data = transcribed(List.of(
                new VideoEmbeddedMetadata.Cue(
                        0, "en", 1_000L, 2_000L, "Hello \"boss\"", 0.9f),
                new VideoEmbeddedMetadata.Cue(
                        1, "es", 3_000L, 4_000L, "Camión listo", 0.8f)));

        VideoEmbeddedMetadata.Payloads payloads =
                VideoEmbeddedMetadata.encode(data, 5_000_000L);
        String description = text(payloads.description);

        assertTrue(description.contains("\"type\":\"description\""));
        assertTrue(description.contains("\"caption\":\"A red truck arrives\""));
        assertTrue(description.contains(
                "\"subtitle_track_mime\":\""
                        + VideoEmbeddedMetadata.SUBTITLE_MIME + "\""));
        assertFalse(description.contains("content://"));
        assertEquals(4, payloads.subtitleEvents.size());
        assertEquals(1_000_000L, payloads.subtitleEvents.get(0).presentationTimeUs);
        assertTrue(text(payloads.subtitleEvents.get(0).payload)
                .contains("\"text\":\"Hello \\\"boss\\\"\""));
        assertEquals(2_000_000L, payloads.subtitleEvents.get(1).presentationTimeUs);
        assertTrue(text(payloads.subtitleEvents.get(1).payload)
                .contains("\"type\":\"clear\""));
        assertEquals(3_000_000L, payloads.subtitleEvents.get(2).presentationTimeUs);
        assertEquals(4_000_000L, payloads.subtitleEvents.get(3).presentationTimeUs);
    }

    @Test
    public void adjacentCuesDoNotCreateConflictingClearTimestamp() {
        VideoEmbeddedMetadata.Payloads payloads = VideoEmbeddedMetadata.encode(
                transcribed(List.of(
                        new VideoEmbeddedMetadata.Cue(
                                0, "en", 0L, 1_000L, "first", 1.0f),
                        new VideoEmbeddedMetadata.Cue(
                                1, "en", 1_000L, 2_000L, "second", 1.0f))),
                2_500_000L);

        assertEquals(3, payloads.subtitleEvents.size());
        assertEquals(0L, payloads.subtitleEvents.get(0).presentationTimeUs);
        assertEquals(1_000_000L, payloads.subtitleEvents.get(1).presentationTimeUs);
        assertEquals(2_000_000L, payloads.subtitleEvents.get(2).presentationTimeUs);
    }

    @Test
    public void noAudioCreatesOnlyDescriptionTrackPayload() {
        VideoEmbeddedMetadata.Data data = new VideoEmbeddedMetadata.Data(
                4L,
                VISION_DIGEST,
                "Silent clip",
                List.of("work"),
                "en",
                0.7f,
                "gemma-vision",
                VISION_DIGEST,
                100L,
                VideoEmbeddedMetadata.STATUS_NO_AUDIO,
                "",
                "",
                "",
                List.of());

        VideoEmbeddedMetadata.Payloads payloads =
                VideoEmbeddedMetadata.encode(data, 1_000_000L);

        assertFalse(payloads.hasSubtitleTrack());
        assertTrue(text(payloads.description).contains("\"subtitle_status\":\"no_audio\""));
        assertTrue(text(payloads.description).contains("\"subtitle_track_mime\":null"));
    }

    @Test
    public void invalidTimelineAndModelStateFailClosed() {
        VideoEmbeddedMetadata.Data beyondDuration = transcribed(List.of(
                new VideoEmbeddedMetadata.Cue(0, "en", 0L, 2_000L, "late", 1.0f)));
        assertThrows(
                IllegalArgumentException.class,
                () -> VideoEmbeddedMetadata.encode(beyondDuration, 1_999_999L));

        VideoEmbeddedMetadata.Data overlap = transcribed(List.of(
                new VideoEmbeddedMetadata.Cue(0, "en", 0L, 1_001L, "first", 1.0f),
                new VideoEmbeddedMetadata.Cue(1, "en", 1_000L, 2_000L, "second", 1.0f)));
        assertThrows(
                IllegalArgumentException.class,
                () -> VideoEmbeddedMetadata.encode(overlap, 3_000_000L));

        VideoEmbeddedMetadata.Data missingCues = transcribed(List.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> VideoEmbeddedMetadata.encode(missingCues, 3_000_000L));
    }

    private static VideoEmbeddedMetadata.Data transcribed(
            List<VideoEmbeddedMetadata.Cue> cues) {
        return new VideoEmbeddedMetadata.Data(
                9L,
                VISION_DIGEST,
                "A red truck arrives",
                List.of("truck", "delivery"),
                "en",
                0.95f,
                "gemma-vision",
                VISION_DIGEST,
                100L,
                VideoEmbeddedMetadata.STATUS_TRANSCRIBED,
                "whisper-small",
                AUDIO_DIGEST,
                "en",
                cues);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
