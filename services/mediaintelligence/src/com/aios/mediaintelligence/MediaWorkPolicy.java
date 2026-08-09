package com.aios.mediaintelligence;

/** Scheduling and write-safety rules shared by observer and worker. */
final class MediaWorkPolicy {
    static final int MIN_DEFERRED_BATTERY_PERCENT = 80;
    static final int CLASS_IMMEDIATE = 0;
    static final int CLASS_DEFERRED = 1;
    static final String BLOCK_UNKNOWN_WORK_CLASS = "unknown_work_class";
    static final String BLOCK_ACTIVE_CALL = "active_call";
    static final String BLOCK_THERMAL_PRESSURE = "thermal_pressure";
    static final String BLOCK_BATTERY_STATE_UNAVAILABLE = "battery_state_unavailable";
    static final String BLOCK_NOT_CHARGING = "not_charging";
    static final String BLOCK_BELOW_BATTERY_THRESHOLD = "below_battery_threshold";

    private MediaWorkPolicy() {}

    static int schedulingClass(String mimeType, int captureGroupSize) {
        boolean video = mimeType != null && mimeType.startsWith("video/");
        return video || captureGroupSize > 1 ? CLASS_DEFERRED : CLASS_IMMEDIATE;
    }

    static boolean isKnownWorkClass(int workClass) {
        return workClass == CLASS_IMMEDIATE || workClass == CLASS_DEFERRED;
    }

    /** Returns a stable retry reason, or {@code null} when work may continue. */
    static String executionBlockReason(
            int workClass,
            boolean callActive,
            boolean thermalPressureHigh,
            boolean charging,
            int batteryPercent) {
        if (!isKnownWorkClass(workClass)) {
            return BLOCK_UNKNOWN_WORK_CLASS;
        }
        if (callActive) {
            return BLOCK_ACTIVE_CALL;
        }
        if (thermalPressureHigh) {
            return BLOCK_THERMAL_PRESSURE;
        }
        if (workClass == CLASS_IMMEDIATE) {
            return null;
        }
        if (batteryPercent < 0 || batteryPercent > 100) {
            return BLOCK_BATTERY_STATE_UNAVAILABLE;
        }
        if (!charging) {
            return BLOCK_NOT_CHARGING;
        }
        if (batteryPercent < MIN_DEFERRED_BATTERY_PERCENT) {
            return BLOCK_BELOW_BATTERY_THRESHOLD;
        }
        return null;
    }

    static boolean deferredConstraintsSatisfied(boolean charging, int batteryPercent) {
        return executionBlockReason(
                CLASS_DEFERRED, false, false, charging, batteryPercent) == null;
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
