package com.aios.media;

/** Bounded description and provenance read from a verified AIOS-enhanced MP4. */
parcelable EnhancedVideoInfo {
    int schemaVersion;
    long mediaGeneration;
    long durationUs;
    long sourceGeneration;
    String sourceSha256;
    String caption;
    String[] tags;
    String language;
    float confidence;
    String visionModelId;
    String visionModelSha256;
    long inferredAtEpochMillis;
    String subtitleStatus;
    String subtitleLanguage;
    int subtitleCueCount;
    String asrModelId;
    String asrModelSha256;
}
