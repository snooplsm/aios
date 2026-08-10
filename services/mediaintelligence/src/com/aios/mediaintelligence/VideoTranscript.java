package com.aios.mediaintelligence;

import com.aios.model.GenerationChunk;
import com.aios.model.InferenceResult;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Strict private subtitle result produced from one video's complete primary audio track. */
final class VideoTranscript {
    static final String STATUS_NOT_APPLICABLE = "not_applicable";
    static final String STATUS_NO_AUDIO = "no_audio";
    static final String STATUS_NO_SPEECH = "no_speech";
    static final String STATUS_TRANSCRIBED = "transcribed";
    static final int MAX_SEGMENTS = 4_096;
    static final int MAX_SEGMENT_CHARS = 4_096;
    static final int MAX_TRANSCRIPT_CHARS = 4 * 1024 * 1024;
    static final long MAX_TIMELINE_MILLIS = 24L * 60L * 60L * 1_000L;

    private static final Pattern MODEL_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    static final class Segment {
        final int sequence;
        final String language;
        final long startMillis;
        final long endMillis;
        final String text;
        final float confidence;

        Segment(
                int sequence,
                String language,
                long startMillis,
                long endMillis,
                String text,
                float confidence) {
            this.sequence = sequence;
            this.language = language;
            this.startMillis = startMillis;
            this.endMillis = endMillis;
            this.text = text;
            this.confidence = confidence;
        }
    }

    final String status;
    final String modelId;
    final String modelDigest;
    final String language;
    final List<Segment> segments;

    private VideoTranscript(
            String status,
            String modelId,
            String modelDigest,
            String language,
            List<Segment> segments) {
        this.status = status;
        this.modelId = modelId;
        this.modelDigest = modelDigest;
        this.language = language;
        this.segments = List.copyOf(segments);
    }

    static VideoTranscript notApplicable() {
        return new VideoTranscript(
                STATUS_NOT_APPLICABLE, "", "", "", List.of());
    }

    static VideoTranscript noAudio() {
        return new VideoTranscript(STATUS_NO_AUDIO, "", "", "", List.of());
    }

    static VideoTranscript fromInference(
            InferenceResult result,
            List<GenerationChunk> chunks,
            long timelineOffsetMillis) {
        if (result == null || result.modelId == null || result.modelDigest == null
                || !MODEL_ID.matcher(result.modelId).matches()
                || !DIGEST.matcher(result.modelDigest).matches()
                || !("en".equals(result.language)
                || "es".equals(result.language) || "und".equals(result.language))
                || chunks == null || timelineOffsetMillis < 0L
                || timelineOffsetMillis > MAX_TIMELINE_MILLIS) {
            throw new IllegalArgumentException("invalid video ASR result");
        }
        List<Segment> segments = new ArrayList<>();
        long previousSourceSequence = -1L;
        long previousEnd = timelineOffsetMillis;
        int totalChars = 0;
        for (GenerationChunk chunk : chunks) {
            if (chunk == null || chunk.sequence <= previousSourceSequence) {
                throw new IllegalArgumentException("video subtitle sequence is invalid");
            }
            previousSourceSequence = chunk.sequence;
            if (!chunk.isFinal) continue;
            String text = chunk.text == null ? "" : chunk.text.trim();
            if (text.isEmpty()) continue;
            if (segments.size() >= MAX_SEGMENTS || text.length() > MAX_SEGMENT_CHARS
                    || totalChars > MAX_TRANSCRIPT_CHARS - text.length()
                    || !("en".equals(chunk.language) || "es".equals(chunk.language))
                    || !Float.isFinite(chunk.confidence)
                    || chunk.confidence < 0.0f || chunk.confidence > 1.0f
                    || chunk.sourceStartMillis < 0L
                    || chunk.sourceEndMillis <= chunk.sourceStartMillis) {
                throw new IllegalArgumentException("video subtitle segment is invalid");
            }
            long start;
            long end;
            try {
                start = Math.addExact(timelineOffsetMillis, chunk.sourceStartMillis);
                end = Math.addExact(timelineOffsetMillis, chunk.sourceEndMillis);
            } catch (ArithmeticException error) {
                throw new IllegalArgumentException("video subtitle timeline overflow", error);
            }
            if (start < previousEnd || end > MAX_TIMELINE_MILLIS) {
                throw new IllegalArgumentException("video subtitle timeline is invalid");
            }
            segments.add(new Segment(
                    segments.size(), chunk.language, start, end, text, chunk.confidence));
            previousEnd = end;
            totalChars += text.length();
        }
        if (segments.isEmpty()) {
            return new VideoTranscript(
                    STATUS_NO_SPEECH,
                    result.modelId,
                    result.modelDigest,
                    result.language,
                    List.of());
        }
        if ("und".equals(result.language)) {
            throw new IllegalArgumentException("transcribed video has no dominant language");
        }
        return new VideoTranscript(
                STATUS_TRANSCRIBED,
                result.modelId,
                result.modelDigest,
                result.language,
                segments);
    }
}
