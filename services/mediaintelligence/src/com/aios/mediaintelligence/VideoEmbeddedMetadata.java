package com.aios.mediaintelligence;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict JSON payloads carried by AIOS ISO-BMFF text-metadata tracks. */
final class VideoEmbeddedMetadata {
    static final String DESCRIPTION_MIME =
            "application/vnd.aios.video-description+json";
    static final String SUBTITLE_MIME =
            "application/vnd.aios.subtitle+json";
    static final int MAX_SAMPLE_BYTES = 64 * 1024;
    static final String STATUS_NO_AUDIO = "no_audio";
    static final String STATUS_NO_SPEECH = "no_speech";
    static final String STATUS_TRANSCRIBED = "transcribed";

    private static final int MAX_CUES = 4_096;
    private static final int MAX_CUE_CHARS = 4_096;
    private static final int MAX_TRANSCRIPT_CHARS = 4 * 1024 * 1024;
    private static final long MAX_TIMELINE_MILLIS = 24L * 60L * 60L * 1_000L;

    private static final int SCHEMA_VERSION = 1;
    private static final Pattern MODEL_ID =
            Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");
    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

    static final class Cue {
        final int sequence;
        final String language;
        final long startMillis;
        final long endMillis;
        final String text;
        final float confidence;

        Cue(
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

    static final class Data {
        final long sourceGeneration;
        final String sourceDigest;
        final String caption;
        final List<String> tags;
        final String language;
        final float confidence;
        final String visionModelId;
        final String visionModelDigest;
        final long inferredAtEpochMillis;
        final String audioStatus;
        final String audioModelId;
        final String audioModelDigest;
        final String audioLanguage;
        final List<Cue> cues;

        Data(
                long sourceGeneration,
                String sourceDigest,
                String caption,
                List<String> tags,
                String language,
                float confidence,
                String visionModelId,
                String visionModelDigest,
                long inferredAtEpochMillis,
                String audioStatus,
                String audioModelId,
                String audioModelDigest,
                String audioLanguage,
                List<Cue> cues) {
            this.sourceGeneration = sourceGeneration;
            this.sourceDigest = sourceDigest;
            this.caption = caption;
            this.tags = tags == null ? null : List.copyOf(tags);
            this.language = language;
            this.confidence = confidence;
            this.visionModelId = visionModelId;
            this.visionModelDigest = visionModelDigest;
            this.inferredAtEpochMillis = inferredAtEpochMillis;
            this.audioStatus = audioStatus;
            this.audioModelId = audioModelId;
            this.audioModelDigest = audioModelDigest;
            this.audioLanguage = audioLanguage;
            this.cues = cues == null ? null : List.copyOf(cues);
        }
    }

    static final class Event {
        final long presentationTimeUs;
        final byte[] payload;

        Event(long presentationTimeUs, byte[] payload) {
            this.presentationTimeUs = presentationTimeUs;
            this.payload = payload.clone();
        }
    }

    static final class Payloads {
        final byte[] description;
        final List<Event> subtitleEvents;

        Payloads(byte[] description, List<Event> subtitleEvents) {
            this.description = description.clone();
            this.subtitleEvents = List.copyOf(subtitleEvents);
        }

        boolean hasSubtitleTrack() {
            return !subtitleEvents.isEmpty();
        }
    }

    private VideoEmbeddedMetadata() {}

    static Payloads encode(Data data, long videoDurationUs) {
        validate(data, videoDurationUs);
        byte[] description = utf8(descriptionJson(data));
        List<Event> events = new ArrayList<>();
        for (int index = 0; index < data.cues.size(); index++) {
            Cue cue = data.cues.get(index);
            events.add(new Event(toMicros(cue.startMillis), utf8(cueJson(cue))));
            long nextStartMillis = index + 1 < data.cues.size()
                    ? data.cues.get(index + 1).startMillis : -1L;
            if (nextStartMillis != cue.endMillis) {
                events.add(new Event(toMicros(cue.endMillis), utf8(clearJson(cue.sequence))));
            }
        }
        return new Payloads(description, events);
    }

    private static String descriptionJson(Data data) {
        StringBuilder output = new StringBuilder(4096);
        output.append('{')
                .append("\"schema_version\":").append(SCHEMA_VERSION).append(',')
                .append("\"type\":\"description\",")
                .append("\"source_generation\":").append(data.sourceGeneration).append(',')
                .append("\"source_sha256\":").append(json(data.sourceDigest)).append(',')
                .append("\"caption\":").append(json(data.caption)).append(',')
                .append("\"tags\":[");
        for (int index = 0; index < data.tags.size(); index++) {
            if (index > 0) output.append(',');
            output.append(json(data.tags.get(index)));
        }
        output.append("],")
                .append("\"language\":").append(json(data.language)).append(',')
                .append("\"confidence\":").append(Float.toString(data.confidence)).append(',')
                .append("\"vision_model_id\":").append(json(data.visionModelId)).append(',')
                .append("\"vision_model_sha256\":")
                .append(json(data.visionModelDigest)).append(',')
                .append("\"inferred_at_epoch_ms\":")
                .append(data.inferredAtEpochMillis).append(',')
                .append("\"subtitle_status\":").append(json(data.audioStatus)).append(',')
                .append("\"subtitle_track_mime\":")
                .append(data.cues.isEmpty() ? "null" : json(SUBTITLE_MIME)).append(',')
                .append("\"subtitle_language\":").append(json(data.audioLanguage)).append(',')
                .append("\"subtitle_cue_count\":").append(data.cues.size()).append(',')
                .append("\"asr_model_id\":").append(json(data.audioModelId)).append(',')
                .append("\"asr_model_sha256\":").append(json(data.audioModelDigest))
                .append('}');
        return output.toString();
    }

    private static String cueJson(Cue cue) {
        return new StringBuilder(cue.text.length() + 192)
                .append('{')
                .append("\"schema_version\":").append(SCHEMA_VERSION).append(',')
                .append("\"type\":\"cue\",")
                .append("\"sequence\":").append(cue.sequence).append(',')
                .append("\"language\":").append(json(cue.language)).append(',')
                .append("\"start_us\":").append(toMicros(cue.startMillis)).append(',')
                .append("\"end_us\":").append(toMicros(cue.endMillis)).append(',')
                .append("\"confidence\":").append(Float.toString(cue.confidence)).append(',')
                .append("\"text\":").append(json(cue.text))
                .append('}')
                .toString();
    }

    private static String clearJson(int afterSequence) {
        return "{\"schema_version\":" + SCHEMA_VERSION
                + ",\"type\":\"clear\",\"after_sequence\":" + afterSequence + '}';
    }

    private static void validate(Data data, long videoDurationUs) {
        if (data == null || data.sourceGeneration < 0L || !validDigest(data.sourceDigest)
                || !validText(data.caption, 1, 2048)
                || data.tags == null || data.tags.size() > 64
                || !validLanguage(data.language) || !validConfidence(data.confidence)
                || !validModel(data.visionModelId, data.visionModelDigest)
                || data.inferredAtEpochMillis <= 0L
                || data.audioStatus == null || data.audioModelId == null
                || data.audioModelDigest == null || data.audioLanguage == null
                || data.cues == null || videoDurationUs <= 0L
                || videoDurationUs > toMicros(MAX_TIMELINE_MILLIS)) {
            throw new IllegalArgumentException("invalid embedded video metadata");
        }
        Set<String> tags = new HashSet<>();
        for (String tag : data.tags) {
            if (!validText(tag, 1, 128) || !tags.add(tag)) {
                throw new IllegalArgumentException("invalid embedded video tags");
            }
        }
        switch (data.audioStatus) {
            case STATUS_NO_AUDIO -> {
                if (!data.audioModelId.isEmpty() || !data.audioModelDigest.isEmpty()
                        || !data.audioLanguage.isEmpty() || !data.cues.isEmpty()) {
                    throw new IllegalArgumentException("invalid embedded no-audio state");
                }
            }
            case STATUS_NO_SPEECH -> {
                if (!validModel(data.audioModelId, data.audioModelDigest)
                        || !validAsrLanguage(data.audioLanguage) || !data.cues.isEmpty()) {
                    throw new IllegalArgumentException("invalid embedded no-speech state");
                }
            }
            case STATUS_TRANSCRIBED -> {
                if (!validModel(data.audioModelId, data.audioModelDigest)
                        || !validLanguage(data.audioLanguage) || data.cues.isEmpty()) {
                    throw new IllegalArgumentException("invalid embedded transcribed state");
                }
            }
            default -> throw new IllegalArgumentException("unsupported embedded subtitle state");
        }
        validateCues(data.cues, videoDurationUs);
    }

    private static void validateCues(List<Cue> cues, long durationUs) {
        if (cues.size() > MAX_CUES) {
            throw new IllegalArgumentException("too many embedded subtitle cues");
        }
        long previousEndMillis = 0L;
        int totalChars = 0;
        for (int index = 0; index < cues.size(); index++) {
            Cue cue = cues.get(index);
            if (cue == null || cue.sequence != index || !validLanguage(cue.language)
                    || cue.startMillis < previousEndMillis || cue.endMillis <= cue.startMillis
                    || toMicros(cue.endMillis) > durationUs
                    || !validText(cue.text, 1, MAX_CUE_CHARS)
                    || !validConfidence(cue.confidence)
                    || totalChars > MAX_TRANSCRIPT_CHARS - cue.text.length()) {
                throw new IllegalArgumentException("invalid embedded subtitle cue");
            }
            previousEndMillis = cue.endMillis;
            totalChars += cue.text.length();
        }
    }

    private static byte[] utf8(String value) {
        byte[] result = value.getBytes(StandardCharsets.UTF_8);
        if (result.length == 0 || result.length > MAX_SAMPLE_BYTES) {
            throw new IllegalArgumentException("embedded metadata sample exceeds its bound");
        }
        return result;
    }

    private static String json(String value) {
        StringBuilder output = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char item = value.charAt(index);
            switch (item) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (item < 0x20) {
                        output.append("\\u")
                                .append(hex(item >>> 12))
                                .append(hex(item >>> 8))
                                .append(hex(item >>> 4))
                                .append(hex(item));
                    } else {
                        output.append(item);
                    }
                }
            }
        }
        return output.append('"').toString();
    }

    private static char hex(int value) {
        return "0123456789abcdef".charAt(value & 0xf);
    }

    private static long toMicros(long millis) {
        try {
            return Math.multiplyExact(millis, 1_000L);
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("embedded subtitle timestamp overflow", error);
        }
    }

    private static boolean validText(String value, int minimum, int maximum) {
        return value != null && value.length() >= minimum && value.length() <= maximum
                && wellFormedUtf16(value) && hasVisibleText(value);
    }

    private static boolean wellFormedUtf16(String value) {
        for (int index = 0; index < value.length(); index++) {
            char item = value.charAt(index);
            if (Character.isHighSurrogate(item)) {
                if (++index >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index))) return false;
            } else if (Character.isLowSurrogate(item)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasVisibleText(String value) {
        for (int index = 0; index < value.length(); index++) {
            char item = value.charAt(index);
            if (item >= 0x20 && !Character.isWhitespace(item)) return true;
        }
        return false;
    }

    private static boolean validLanguage(String value) {
        return "en".equals(value) || "es".equals(value);
    }

    private static boolean validAsrLanguage(String value) {
        return validLanguage(value) || "und".equals(value);
    }

    private static boolean validConfidence(float value) {
        return Float.isFinite(value) && value >= 0.0f && value <= 1.0f;
    }

    private static boolean validModel(String id, String digest) {
        return id != null && MODEL_ID.matcher(id).matches() && validDigest(digest);
    }

    private static boolean validDigest(String value) {
        return value != null && DIGEST.matcher(value).matches();
    }
}
