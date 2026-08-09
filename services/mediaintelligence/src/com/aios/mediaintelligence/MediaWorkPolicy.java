package com.aios.mediaintelligence;

/** Scheduling and write-safety rules shared by observer and worker. */
final class MediaWorkPolicy {
    static final int MIN_DEFERRED_BATTERY_PERCENT = 80;
    static final int CLASS_IMMEDIATE = 0;
    static final int CLASS_DEFERRED = 1;

    private MediaWorkPolicy() {}

    static int schedulingClass(String mimeType, int captureGroupSize) {
        boolean video = mimeType != null && mimeType.startsWith("video/");
        return video || captureGroupSize > 1 ? CLASS_DEFERRED : CLASS_IMMEDIATE;
    }

    static boolean deferredConstraintsSatisfied(boolean charging, int batteryPercent) {
        return charging && batteryPercent >= MIN_DEFERRED_BATTERY_PERCENT;
    }

    static boolean mayEmbed(
            String mimeType,
            boolean motionPhoto,
            boolean ultraHdr,
            boolean raw,
            boolean writerRoundTripVerified) {
        return writerRoundTripVerified
                && "image/jpeg".equals(mimeType)
                && !motionPhoto
                && !ultraHdr
                && !raw;
    }
}
