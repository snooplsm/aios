package com.aios.callintelligence;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

/** Pure, dual-clock retention policy shared by Android storage and host tests. */
final class CallArtifactRetention {
    static final long RETENTION_MILLIS = 24L * 60L * 60L * 1000L;
    static final long UNREADABLE_EXPIRY = Long.MIN_VALUE;

    static final class Deadline {
        final String bootIdentity;
        final long createdAtEpochMillis;
        final long expiresAtEpochMillis;
        final long createdAtElapsedRealtimeMillis;
        final long expiresAtElapsedRealtimeMillis;

        Deadline(
                String bootIdentity,
                long createdAtEpochMillis,
                long expiresAtEpochMillis,
                long createdAtElapsedRealtimeMillis,
                long expiresAtElapsedRealtimeMillis) {
            this.bootIdentity = bootIdentity;
            this.createdAtEpochMillis = createdAtEpochMillis;
            this.expiresAtEpochMillis = expiresAtEpochMillis;
            this.createdAtElapsedRealtimeMillis = createdAtElapsedRealtimeMillis;
            this.expiresAtElapsedRealtimeMillis = expiresAtElapsedRealtimeMillis;
        }

        static Deadline create(
                String bootIdentity,
                long createdAtEpochMillis,
                long createdAtElapsedRealtimeMillis) throws IOException {
            return new Deadline(
                    bootIdentity,
                    createdAtEpochMillis,
                    expiresAt(createdAtEpochMillis),
                    createdAtElapsedRealtimeMillis,
                    expiresAt(createdAtElapsedRealtimeMillis));
        }

        static Deadline unreadable() {
            return new Deadline(
                    "", UNREADABLE_EXPIRY, UNREADABLE_EXPIRY,
                    UNREADABLE_EXPIRY, UNREADABLE_EXPIRY);
        }
    }

    interface DeadlineReader {
        Deadline readDeadline(File directory);
    }

    interface BeforeDelete {
        void beforeDelete(File directory);
    }

    private CallArtifactRetention() {}

    static long expiresAt(long createdAtMillis) throws IOException {
        try {
            return Math.addExact(createdAtMillis, RETENTION_MILLIS);
        } catch (ArithmeticException overflow) {
            throw new IOException("call artifact expiry overflows clock", overflow);
        }
    }

    static long validatedExpiry(long createdAtMillis, long storedExpiryMillis) {
        try {
            long expected = expiresAt(createdAtMillis);
            return expected == storedExpiryMillis
                    ? storedExpiryMillis
                    : UNREADABLE_EXPIRY;
        } catch (IOException invalidCreationTime) {
            return UNREADABLE_EXPIRY;
        }
    }

    static boolean isValid(Deadline deadline) {
        return deadline != null
                && deadline.bootIdentity != null
                && !deadline.bootIdentity.isBlank()
                && deadline.createdAtElapsedRealtimeMillis >= 0L
                && validatedExpiry(
                        deadline.createdAtEpochMillis,
                        deadline.expiresAtEpochMillis) != UNREADABLE_EXPIRY
                && validatedExpiry(
                        deadline.createdAtElapsedRealtimeMillis,
                        deadline.expiresAtElapsedRealtimeMillis) != UNREADABLE_EXPIRY;
    }

    /**
     * A wall-clock jump forward may shorten retention, but a rollback cannot extend it.
     * A previous-boot deadline is deleted fail-closed because elapsed realtime resets
     * at boot and no offline clock can prove that the maximum has not passed.
     */
    static boolean isExpired(
            Deadline deadline,
            String currentBootIdentity,
            long nowEpochMillis,
            long nowElapsedRealtimeMillis) {
        if (!isValid(deadline)
                || !Objects.equals(deadline.bootIdentity, currentBootIdentity)
                || nowElapsedRealtimeMillis < deadline.createdAtElapsedRealtimeMillis) {
            return true;
        }
        return deadline.expiresAtEpochMillis <= nowEpochMillis
                || deadline.expiresAtElapsedRealtimeMillis <= nowElapsedRealtimeMillis;
    }

    static boolean canResume(
            Deadline deadline,
            String currentBootIdentity,
            long nowEpochMillis,
            long nowElapsedRealtimeMillis) {
        return !isExpired(
                deadline, currentBootIdentity, nowEpochMillis, nowElapsedRealtimeMillis);
    }

    static void cleanup(
            File callsDirectory,
            String currentBootIdentity,
            long nowEpochMillis,
            long nowElapsedRealtimeMillis,
            DeadlineReader deadlineReader) {
        cleanup(
                callsDirectory,
                currentBootIdentity,
                nowEpochMillis,
                nowElapsedRealtimeMillis,
                deadlineReader,
                directory -> {});
    }

    static void cleanup(
            File callsDirectory,
            String currentBootIdentity,
            long nowEpochMillis,
            long nowElapsedRealtimeMillis,
            DeadlineReader deadlineReader,
            BeforeDelete beforeDelete) {
        File[] sessions = callsDirectory.listFiles(File::isDirectory);
        if (sessions == null) {
            return;
        }
        for (File directory : sessions) {
            if (isExpired(
                    deadlineReader.readDeadline(directory),
                    currentBootIdentity,
                    nowEpochMillis,
                    nowElapsedRealtimeMillis)) {
                beforeDelete.beforeDelete(directory);
                deleteTree(directory);
            }
        }
    }

    static long nextElapsedAlarm(
            File callsDirectory,
            String currentBootIdentity,
            long nowEpochMillis,
            long nowElapsedRealtimeMillis,
            DeadlineReader deadlineReader) {
        File[] sessions = callsDirectory.listFiles(File::isDirectory);
        if (sessions == null || sessions.length == 0) {
            return Long.MAX_VALUE;
        }
        long next = Long.MAX_VALUE;
        for (File directory : sessions) {
            Deadline deadline = deadlineReader.readDeadline(directory);
            if (isExpired(
                    deadline,
                    currentBootIdentity,
                    nowEpochMillis,
                    nowElapsedRealtimeMillis)) {
                return nowElapsedRealtimeMillis;
            }
            next = Math.min(next, deadline.expiresAtElapsedRealtimeMillis);
        }
        return next;
    }

    static boolean deleteTree(File file) {
        boolean removed = true;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                removed &= deleteTree(child);
            }
        }
        // Best effort: an undeleted directory remains visible to the next sweep.
        return (!file.exists() || file.delete()) && removed;
    }
}
