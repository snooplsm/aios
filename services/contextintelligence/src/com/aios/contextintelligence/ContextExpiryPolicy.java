package com.aios.contextintelligence;

import java.util.Objects;

/** Pure dual-clock expiry policy for call-derived communication context. */
final class ContextExpiryPolicy {
    private ContextExpiryPolicy() {}

    static boolean isExpired(
            long createdAtEpochMillis,
            long expiresAtEpochMillis,
            String expiryBootIdentity,
            long createdAtElapsedRealtimeMillis,
            long expiresAtElapsedRealtimeMillis,
            String currentBootIdentity,
            long nowEpochMillis,
            long nowElapsedRealtimeMillis) {
        if (expiresAtEpochMillis == 0L) return false;
        return !isWellFormed(
                        createdAtEpochMillis,
                        expiresAtEpochMillis,
                        createdAtElapsedRealtimeMillis,
                        expiresAtElapsedRealtimeMillis)
                || expiryBootIdentity == null
                || expiryBootIdentity.isBlank()
                || currentBootIdentity == null
                || currentBootIdentity.isBlank()
                || nowEpochMillis <= 0L
                || nowElapsedRealtimeMillis < 0L
                || !Objects.equals(expiryBootIdentity, currentBootIdentity)
                || nowElapsedRealtimeMillis < createdAtElapsedRealtimeMillis
                || nowEpochMillis >= expiresAtEpochMillis
                || nowElapsedRealtimeMillis >= expiresAtElapsedRealtimeMillis;
    }

    static boolean isWellFormed(
            long createdAtEpochMillis,
            long expiresAtEpochMillis,
            long createdAtElapsedRealtimeMillis,
            long expiresAtElapsedRealtimeMillis) {
        if (createdAtEpochMillis <= 0L || createdAtElapsedRealtimeMillis < 0L) return false;
        try {
            return Math.addExact(
                    createdAtEpochMillis,
                    ContextPolicy.CALL_ARTIFACT_TTL_MILLIS) == expiresAtEpochMillis
                    && Math.addExact(
                            createdAtElapsedRealtimeMillis,
                            ContextPolicy.CALL_ARTIFACT_TTL_MILLIS)
                    == expiresAtElapsedRealtimeMillis;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }
}
