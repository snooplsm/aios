package com.aios.mediaintelligence;

/** Fail-safe decision policy for a journaled enhanced-video MediaStore row. */
final class VideoExportRecoveryPolicy {
    static final int FORGET_ABSENT = 0;
    static final int DELETE_PENDING = 1;
    static final int PRESERVE_PUBLISHED = 2;
    static final int FORGET_UNTRUSTED = 3;

    private VideoExportRecoveryPolicy() {}

    static int decide(
            boolean exists,
            boolean pending,
            boolean ownerMatches,
            boolean mp4MimeMatches,
            boolean pendingMarkerMatches) {
        if (!exists) return FORGET_ABSENT;
        if (!ownerMatches || !mp4MimeMatches) return FORGET_UNTRUSTED;
        if (!pending) return PRESERVE_PUBLISHED;
        return pendingMarkerMatches ? DELETE_PENDING : FORGET_UNTRUSTED;
    }
}
