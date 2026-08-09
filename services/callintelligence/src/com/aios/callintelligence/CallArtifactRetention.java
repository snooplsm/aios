package com.aios.callintelligence;

import java.io.File;
import java.io.IOException;

/** Pure retention policy shared by Android storage and host tests. */
final class CallArtifactRetention {
    static final long RETENTION_MILLIS = 24L * 60L * 60L * 1000L;
    static final long UNREADABLE_EXPIRY = Long.MIN_VALUE;

    interface ExpiryReader {
        long readExpiry(File directory);
    }

    interface BeforeDelete {
        void beforeDelete(File directory);
    }

    private CallArtifactRetention() {}

    static long expiresAt(long createdAtEpochMillis) throws IOException {
        try {
            return Math.addExact(createdAtEpochMillis, RETENTION_MILLIS);
        } catch (ArithmeticException overflow) {
            throw new IOException("call artifact expiry overflows epoch time", overflow);
        }
    }

    static boolean isExpired(long expiresAtEpochMillis, long nowEpochMillis) {
        return expiresAtEpochMillis <= nowEpochMillis;
    }

    static long validatedExpiry(
            long createdAtEpochMillis,
            long storedExpiryEpochMillis) {
        try {
            long expected = expiresAt(createdAtEpochMillis);
            return expected == storedExpiryEpochMillis
                    ? storedExpiryEpochMillis
                    : UNREADABLE_EXPIRY;
        } catch (IOException invalidCreationTime) {
            return UNREADABLE_EXPIRY;
        }
    }

    static void cleanup(
            File callsDirectory,
            long nowEpochMillis,
            ExpiryReader expiryReader) {
        cleanup(callsDirectory, nowEpochMillis, expiryReader, directory -> {});
    }

    static void cleanup(
            File callsDirectory,
            long nowEpochMillis,
            ExpiryReader expiryReader,
            BeforeDelete beforeDelete) {
        File[] sessions = callsDirectory.listFiles(File::isDirectory);
        if (sessions == null) {
            return;
        }
        for (File directory : sessions) {
            if (isExpired(expiryReader.readExpiry(directory), nowEpochMillis)) {
                beforeDelete.beforeDelete(directory);
                deleteTree(directory);
            }
        }
    }

    static long nextExpiry(File callsDirectory, ExpiryReader expiryReader) {
        File[] sessions = callsDirectory.listFiles(File::isDirectory);
        if (sessions == null || sessions.length == 0) {
            return Long.MAX_VALUE;
        }
        long next = Long.MAX_VALUE;
        for (File directory : sessions) {
            next = Math.min(next, expiryReader.readExpiry(directory));
        }
        return next;
    }

    /**
     * Converts an absolute stored expiry into an elapsed-realtime alarm.
     * Once scheduled, manual wall-clock rollback cannot extend the timer.
     */
    static long elapsedAlarmTrigger(
            long nowEpochMillis,
            long nowElapsedMillis,
            long expiresAtEpochMillis) {
        if (expiresAtEpochMillis <= nowEpochMillis) {
            return nowElapsedMillis;
        }
        try {
            long remaining = Math.subtractExact(expiresAtEpochMillis, nowEpochMillis);
            return Math.addExact(nowElapsedMillis, remaining);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        // Best effort: an undeleted directory remains visible to the next sweep.
        file.delete();
    }
}
