package com.aios.mediaintelligence;

/** Immutable completed MediaStore observation admitted to the durable queue. */
final class ObservedMedia {
    final String uri;
    final long generation;
    final String mimeType;
    final long observedAtEpochMillis;

    ObservedMedia(
            String uri,
            long generation,
            String mimeType,
            long observedAtEpochMillis) {
        if (uri == null || uri.isBlank() || generation < 0L
                || mimeType == null || mimeType.isBlank()
                || observedAtEpochMillis <= 0L) {
            throw new IllegalArgumentException("invalid media observation");
        }
        this.uri = uri;
        this.generation = generation;
        this.mimeType = mimeType;
        this.observedAtEpochMillis = observedAtEpochMillis;
    }
}
