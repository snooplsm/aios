package com.aios.mediaintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.aios.model.GenerationChunk;
import com.aios.model.InferenceResult;

import org.junit.Test;

import java.util.List;

public final class VideoTranscriptTest {
    private static final String DIGEST = "a".repeat(64);

    @Test
    public void keepsOnlyFinalTimestampedEnglishAndSpanishSegments() {
        GenerationChunk partial = chunk(0L, "ignored", "en", false, 0L, 1_000L);
        GenerationChunk english = chunk(1L, " hello ", "en", true, 0L, 4_000L);
        GenerationChunk spanish = chunk(2L, "mundo", "es", true, 4_000L, 8_000L);

        VideoTranscript value = VideoTranscript.fromInference(
                result("es"), List.of(partial, english, spanish), 250L);

        assertEquals(VideoTranscript.STATUS_TRANSCRIBED, value.status);
        assertEquals(2, value.segments.size());
        assertEquals(250L, value.segments.get(0).startMillis);
        assertEquals(8_250L, value.segments.get(1).endMillis);
        assertEquals("hello", value.segments.get(0).text);
    }

    @Test
    public void emptyDecodedAudioIsDistinctFromMissingAudioTrack() {
        VideoTranscript silence = VideoTranscript.fromInference(
                result("und"), List.of(), 0L);
        assertEquals(VideoTranscript.STATUS_NO_SPEECH, silence.status);
        assertEquals(VideoTranscript.STATUS_NO_AUDIO, VideoTranscript.noAudio().status);
    }

    @Test
    public void overlappingOrNonBilingualSegmentsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> VideoTranscript.fromInference(
                result("en"),
                List.of(
                        chunk(0L, "one", "en", true, 0L, 4_000L),
                        chunk(1L, "two", "en", true, 3_000L, 5_000L)),
                0L));
        assertThrows(IllegalArgumentException.class, () -> VideoTranscript.fromInference(
                result("en"),
                List.of(chunk(0L, "bonjour", "fr", true, 0L, 1_000L)),
                0L));
    }

    private static InferenceResult result(String language) {
        InferenceResult value = new InferenceResult();
        value.modelId = "whisper-small";
        value.modelDigest = DIGEST;
        value.language = language;
        return value;
    }

    private static GenerationChunk chunk(
            long sequence,
            String text,
            String language,
            boolean isFinal,
            long start,
            long end) {
        GenerationChunk value = new GenerationChunk();
        value.sequence = sequence;
        value.text = text;
        value.language = language;
        value.isFinal = isFinal;
        value.confidence = 0.5f;
        value.sourceStartMillis = start;
        value.sourceEndMillis = end;
        return value;
    }
}
