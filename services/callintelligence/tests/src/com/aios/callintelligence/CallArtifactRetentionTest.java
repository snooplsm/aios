package com.aios.callintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class CallArtifactRetentionTest {
    private static final String BOOT = "android-boot:42";

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void immediateDeletionRemovesNestedArtifactTree() throws Exception {
        File root = temporary.newFolder("aios-call-discard");
        File session = new File(root, "session");
        File nested = new File(session, "nested");
        assertTrue(nested.mkdirs());
        assertTrue(new File(nested, "rx.pcm").createNewFile());

        assertTrue(CallArtifactRetention.deleteTree(session));

        assertFalse(session.exists());
    }

    @Test
    public void bothDeadlinesAreExactlyTwentyFourHoursAfterCreation() throws Exception {
        CallArtifactRetention.Deadline deadline = deadline(1_700_000_000_000L, 12_000L);

        assertEquals(1_700_086_400_000L, deadline.expiresAtEpochMillis);
        assertEquals(86_412_000L, deadline.expiresAtElapsedRealtimeMillis);
        assertTrue(CallArtifactRetention.isValid(deadline));
    }

    @Test
    public void restartCanResumeOnlyAnUnexpiredSameBootExactWindow() throws Exception {
        CallArtifactRetention.Deadline deadline = deadline(1_700_000_000_000L, 12_000L);

        assertTrue(CallArtifactRetention.canResume(
                deadline, BOOT, deadline.expiresAtEpochMillis - 1L,
                deadline.expiresAtElapsedRealtimeMillis - 1L));
        assertFalse(CallArtifactRetention.canResume(
                deadline, BOOT, deadline.expiresAtEpochMillis,
                deadline.expiresAtElapsedRealtimeMillis - 1L));
        assertFalse(CallArtifactRetention.canResume(
                deadline, "android-boot:43", deadline.createdAtEpochMillis + 1L, 1L));
    }

    @Test
    public void wallClockRollbackCannotExtendMonotonicDeadline() throws Exception {
        CallArtifactRetention.Deadline deadline = deadline(1_700_000_000_000L, 12_000L);

        assertTrue(CallArtifactRetention.isExpired(
                deadline,
                BOOT,
                deadline.createdAtEpochMillis - 6L * 60L * 60L * 1000L,
                deadline.expiresAtElapsedRealtimeMillis));
    }

    @Test
    public void rebootAndElapsedClockRegressionFailClosed() throws Exception {
        CallArtifactRetention.Deadline deadline = deadline(1_700_000_000_000L, 12_000L);

        assertTrue(CallArtifactRetention.isExpired(
                deadline, "android-boot:43", deadline.createdAtEpochMillis + 1L, 1L));
        assertTrue(CallArtifactRetention.isExpired(
                deadline, BOOT, deadline.createdAtEpochMillis + 1L, 11_999L));
    }

    @Test
    public void wallClockJumpForwardMayShortenButNeverLengthenRetention()
            throws Exception {
        CallArtifactRetention.Deadline deadline = deadline(1_700_000_000_000L, 12_000L);

        assertTrue(CallArtifactRetention.isExpired(
                deadline,
                BOOT,
                deadline.expiresAtEpochMillis,
                deadline.createdAtElapsedRealtimeMillis + 1L));
    }

    @Test
    public void expiryOverflowFailsClosed() throws Exception {
        try {
            CallArtifactRetention.Deadline.create(BOOT, Long.MAX_VALUE, 1L);
            fail("overflow must not create an immortal artifact");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("overflows"));
        }
    }

    @Test
    public void eitherTamperedDeadlineInvalidatesTheArtifact() throws Exception {
        CallArtifactRetention.Deadline valid = deadline(1_700_000_000_000L, 12_000L);
        CallArtifactRetention.Deadline wallTampered = new CallArtifactRetention.Deadline(
                BOOT,
                valid.createdAtEpochMillis,
                valid.expiresAtEpochMillis + 1L,
                valid.createdAtElapsedRealtimeMillis,
                valid.expiresAtElapsedRealtimeMillis);
        CallArtifactRetention.Deadline elapsedTampered = new CallArtifactRetention.Deadline(
                BOOT,
                valid.createdAtEpochMillis,
                valid.expiresAtEpochMillis,
                valid.createdAtElapsedRealtimeMillis,
                valid.expiresAtElapsedRealtimeMillis - 1L);

        assertFalse(CallArtifactRetention.isValid(wallTampered));
        assertFalse(CallArtifactRetention.isValid(elapsedTampered));
        assertTrue(CallArtifactRetention.isExpired(wallTampered, BOOT, 0L, 0L));
        assertTrue(CallArtifactRetention.isExpired(elapsedTampered, BOOT, 0L, 0L));
    }

    @Test
    public void cleanupDeletesAtEitherBoundaryButNotOneMillisecondEarly()
            throws Exception {
        File calls = temporary.newFolder("calls");
        File active = session(calls, "active", true);
        File wallExpired = session(calls, "wall-expired", true);
        File elapsedExpired = session(calls, "elapsed-expired", true);
        Map<String, CallArtifactRetention.Deadline> deadlines = new HashMap<>();
        deadlines.put(active.getName(), deadline(1_000L, 100L));
        deadlines.put(wallExpired.getName(), deadline(0L, 101L));
        deadlines.put(elapsedExpired.getName(), deadline(1_001L, 0L));
        Set<String> closedBeforeDelete = new HashSet<>();

        CallArtifactRetention.cleanup(
                calls,
                BOOT,
                CallArtifactRetention.RETENTION_MILLIS,
                CallArtifactRetention.RETENTION_MILLIS,
                directory -> deadlines.get(directory.getName()),
                directory -> {
                    assertTrue(directory.exists());
                    closedBeforeDelete.add(directory.getName());
                });

        assertTrue(active.isDirectory());
        assertFalse(wallExpired.exists());
        assertFalse(elapsedExpired.exists());
        assertEquals(Set.of("wall-expired", "elapsed-expired"), closedBeforeDelete);
    }

    @Test
    public void unreadableAndPreviousBootSessionsAreDeleted() throws Exception {
        File calls = temporary.newFolder("calls-unreadable");
        File unreadable = session(calls, "unreadable", true);
        File previousBoot = session(calls, "previous-boot", true);
        Map<String, CallArtifactRetention.Deadline> deadlines = new HashMap<>();
        deadlines.put(unreadable.getName(), CallArtifactRetention.Deadline.unreadable());
        deadlines.put(previousBoot.getName(),
                CallArtifactRetention.Deadline.create("android-boot:41", 1_000L, 100L));

        CallArtifactRetention.cleanup(
                calls, BOOT, 1_001L, 101L,
                directory -> deadlines.get(directory.getName()));

        assertFalse(unreadable.exists());
        assertFalse(previousBoot.exists());
    }

    @Test
    public void nearestAlarmUsesMonotonicDeadlineAndExpiredWorkRunsNow()
            throws Exception {
        File calls = temporary.newFolder("calls-alarm");
        File later = session(calls, "later", false);
        File sooner = session(calls, "sooner", false);
        File loose = new File(calls, "not-a-session");
        assertTrue(loose.createNewFile());
        Map<String, CallArtifactRetention.Deadline> deadlines = new HashMap<>();
        deadlines.put(later.getName(), deadline(3_000L, 300L));
        deadlines.put(sooner.getName(), deadline(2_000L, 200L));

        assertEquals(200L + CallArtifactRetention.RETENTION_MILLIS,
                CallArtifactRetention.nextElapsedAlarm(
                        calls, BOOT, 3_001L, 301L,
                        directory -> deadlines.get(directory.getName())));

        deadlines.put(sooner.getName(), CallArtifactRetention.Deadline.unreadable());
        assertEquals(301L, CallArtifactRetention.nextElapsedAlarm(
                calls, BOOT, 3_001L, 301L,
                directory -> deadlines.get(directory.getName())));
    }

    @Test
    public void emptyStoreCancelsAlarm() throws Exception {
        File calls = temporary.newFolder("calls-empty");

        assertEquals(Long.MAX_VALUE, CallArtifactRetention.nextElapsedAlarm(
                calls, BOOT, 1L, 1L,
                directory -> CallArtifactRetention.Deadline.unreadable()));
    }

    private static CallArtifactRetention.Deadline deadline(
            long createdAtEpochMillis,
            long createdAtElapsedRealtimeMillis) throws IOException {
        return CallArtifactRetention.Deadline.create(
                BOOT, createdAtEpochMillis, createdAtElapsedRealtimeMillis);
    }

    private static File session(File calls, String name, boolean nested)
            throws IOException {
        File directory = new File(calls, name);
        assertTrue(directory.mkdir());
        File contentDirectory = directory;
        if (nested) {
            contentDirectory = new File(directory, "nested");
            assertTrue(contentDirectory.mkdir());
        }
        try (FileOutputStream stream = new FileOutputStream(
                new File(contentDirectory, "artifact.pcm"))) {
            stream.write(new byte[] {1, 2, 3});
        }
        return directory;
    }
}
