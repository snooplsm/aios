package com.aios.mediaintelligence;

/** Privacy-minimized timing values for one successfully indexed media item. */
final class MediaTiming {
    static final String KIND_PHOTO = "photo";
    static final String KIND_VIDEO = "video";
    static final long UNKNOWN_MILLIS = -1L;
    static final long MAX_VIDEO_AUDIO_DURATION_MILLIS = 24L * 60L * 60L * 1_000L;

    static final class Sample {
        final String mediaKind;
        final long observedToIndexMillis;
        final long queueToStartMillis;
        final long processingMillis;
        final long inputPreparationMillis;
        final long modelRequestMillis;
        final long videoAudioDurationMillis;
        final long videoAudioPipelineMillis;
        final long completedAtEpochMillis;

        Sample(
                String mediaKind,
                long observedToIndexMillis,
                long queueToStartMillis,
                long processingMillis,
                long inputPreparationMillis,
                long modelRequestMillis,
                long videoAudioDurationMillis,
                long videoAudioPipelineMillis,
                long completedAtEpochMillis) {
            boolean photo = KIND_PHOTO.equals(mediaKind);
            boolean video = KIND_VIDEO.equals(mediaKind);
            boolean legacyVideoAudio = video
                    && videoAudioDurationMillis == UNKNOWN_MILLIS
                    && videoAudioPipelineMillis == UNKNOWN_MILLIS;
            boolean measuredVideoAudio = video
                    && videoAudioDurationMillis >= 0L
                    && videoAudioDurationMillis <= MAX_VIDEO_AUDIO_DURATION_MILLIS
                    && videoAudioPipelineMillis >= 0L
                    && videoAudioPipelineMillis <= modelRequestMillis;
            if (!photo && !video) {
                throw new IllegalArgumentException("unknown media timing kind");
            }
            requireWallDuration(observedToIndexMillis);
            requireWallDuration(queueToStartMillis);
            if (processingMillis < 0L || inputPreparationMillis < 0L
                    || modelRequestMillis < 0L
                    || modelRequestMillis > processingMillis
                    || inputPreparationMillis > processingMillis - modelRequestMillis
                    || (photo && (videoAudioDurationMillis != UNKNOWN_MILLIS
                    || videoAudioPipelineMillis != UNKNOWN_MILLIS))
                    || (video && !legacyVideoAudio && !measuredVideoAudio)
                    || completedAtEpochMillis <= 0L) {
                throw new IllegalArgumentException("invalid media timing sample");
            }
            this.mediaKind = mediaKind;
            this.observedToIndexMillis = observedToIndexMillis;
            this.queueToStartMillis = queueToStartMillis;
            this.processingMillis = processingMillis;
            this.inputPreparationMillis = inputPreparationMillis;
            this.modelRequestMillis = modelRequestMillis;
            this.videoAudioDurationMillis = videoAudioDurationMillis;
            this.videoAudioPipelineMillis = videoAudioPipelineMillis;
            this.completedAtEpochMillis = completedAtEpochMillis;
        }
    }

    private MediaTiming() {}

    static Sample completed(
            String mimeType,
            long observedAtEpochMillis,
            long startedAtEpochMillis,
            long completedAtEpochMillis,
            long processingMillis,
            long inputPreparationMillis,
            long modelRequestMillis,
            long videoAudioDurationMillis,
            long videoAudioPipelineMillis) {
        return new Sample(
                kind(mimeType),
                wallDurationOrUnknown(observedAtEpochMillis, completedAtEpochMillis),
                wallDurationOrUnknown(observedAtEpochMillis, startedAtEpochMillis),
                processingMillis,
                inputPreparationMillis,
                modelRequestMillis,
                videoAudioDurationMillis,
                videoAudioPipelineMillis,
                completedAtEpochMillis);
    }

    static String kind(String mimeType) {
        if (mimeType != null && mimeType.startsWith("image/")) return KIND_PHOTO;
        if (mimeType != null && mimeType.startsWith("video/")) return KIND_VIDEO;
        throw new IllegalArgumentException("unsupported media timing MIME type");
    }

    static long elapsedDuration(long startedMillis, long completedMillis) {
        if (startedMillis < 0L || completedMillis < startedMillis) {
            throw new IllegalArgumentException("invalid elapsed-realtime interval");
        }
        return completedMillis - startedMillis;
    }

    static long wallDurationOrUnknown(long startedEpochMillis, long completedEpochMillis) {
        if (startedEpochMillis <= 0L || completedEpochMillis < startedEpochMillis) {
            return UNKNOWN_MILLIS;
        }
        return completedEpochMillis - startedEpochMillis;
    }

    private static void requireWallDuration(long value) {
        if (value < 0L && value != UNKNOWN_MILLIS) {
            throw new IllegalArgumentException("invalid wall-clock duration");
        }
    }
}
