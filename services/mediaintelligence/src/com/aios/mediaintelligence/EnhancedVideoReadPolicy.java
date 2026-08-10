package com.aios.mediaintelligence;

/** Pure bounds used by the enhanced-video Binder and extractor boundary. */
final class EnhancedVideoReadPolicy {
    static final String OWNER_PACKAGE = "com.aios.mediaintelligence";
    static final int MAX_TRACKS = 34;
    static final int MAX_CUES = 4_096;
    static final int MAX_PAGE_CUES = 16;
    static final int MAX_EVENT_SAMPLES = MAX_CUES * 2;
    static final long MAX_TOTAL_METADATA_BYTES = 32L * 1024L * 1024L;
    static final long MAX_DURATION_US = 24L * 60L * 60L * 1_000_000L;

    private EnhancedVideoReadPolicy() {}

    static void validateMediaRow(
            String requestedUri,
            String canonicalUri,
            String mimeType,
            String ownerPackage,
            int pending,
            int trashed,
            long generation) {
        if (requestedUri == null || !requestedUri.equals(canonicalUri)
                || !"video/mp4".equals(mimeType)
                || !OWNER_PACKAGE.equals(ownerPackage)
                || pending != 0 || trashed != 0 || generation < 0L) {
            throw new IllegalArgumentException("media is not a published AIOS MP4");
        }
    }

    static void validateContainer(
            int trackCount,
            int descriptionTracks,
            int subtitleTracks,
            boolean hasVideo,
            long durationUs) {
        if (trackCount <= 0 || trackCount > MAX_TRACKS
                || descriptionTracks != 1
                || subtitleTracks < 0 || subtitleTracks > 1
                || !hasVideo || durationUs <= 0L || durationUs > MAX_DURATION_US) {
            throw new IllegalArgumentException("invalid AIOS-enhanced MP4 container");
        }
    }

    static int pageEnd(int startSequence, int limit, int cueCount) {
        if (cueCount < 0 || cueCount > MAX_CUES
                || startSequence < 0 || startSequence > cueCount
                || limit <= 0 || limit > MAX_PAGE_CUES) {
            throw new IllegalArgumentException("invalid enhanced-video cue page");
        }
        return Math.min(cueCount, startSequence + limit);
    }
}
