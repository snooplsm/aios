package com.aios.modelbroker;

/** Distinguishes finite inference from explicitly lifecycle-bound streaming work. */
final class SessionDeadlinePolicy {
    static final long LIFECYCLE_BOUND = Long.MAX_VALUE;
    static final long MAX_FINITE_HORIZON_MILLIS = 5L * 60L * 1_000L;

    private SessionDeadlinePolicy() {}

    static boolean isLifecycleBound(
            String capability, long deadlineElapsedRealtimeMillis) {
        return "streaming_asr".equals(capability)
                && deadlineElapsedRealtimeMillis == LIFECYCLE_BOUND;
    }

    static boolean validMode(String capability, long deadlineElapsedRealtimeMillis) {
        return deadlineElapsedRealtimeMillis != LIFECYCLE_BOUND
                || isLifecycleBound(capability, deadlineElapsedRealtimeMillis);
    }

    static boolean validAt(
            String capability, long deadlineElapsedRealtimeMillis, long nowMillis) {
        if (nowMillis < 0L || !validMode(capability, deadlineElapsedRealtimeMillis)) {
            return false;
        }
        if (isLifecycleBound(capability, deadlineElapsedRealtimeMillis)) {
            return true;
        }
        return deadlineElapsedRealtimeMillis > nowMillis
                && deadlineElapsedRealtimeMillis - nowMillis
                <= MAX_FINITE_HORIZON_MILLIS;
    }

    static boolean shouldTrack(String capability, long deadlineElapsedRealtimeMillis) {
        if (!validMode(capability, deadlineElapsedRealtimeMillis)) {
            throw new IllegalArgumentException(
                    "only streaming ASR may use a lifecycle-bound deadline");
        }
        return !isLifecycleBound(capability, deadlineElapsedRealtimeMillis);
    }
}
