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

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void expiryIsExactlyTwentyFourHoursAfterCreation() throws Exception {
        long createdAt = 1_700_000_000_000L;

        assertEquals(createdAt + 86_400_000L,
                CallArtifactRetention.expiresAt(createdAt));
    }

    @Test
    public void restartCanResumeOnlyAnUnexpiredExactWindow() throws Exception {
        long createdAt = 1_700_000_000_000L;
        long expiresAt = CallArtifactRetention.expiresAt(createdAt);

        assertTrue(CallArtifactRetention.canResume(
                createdAt, expiresAt, expiresAt - 1L));
        assertFalse(CallArtifactRetention.canResume(
                createdAt, expiresAt, expiresAt));
        assertFalse(CallArtifactRetention.canResume(
                createdAt, expiresAt + 1L, createdAt + 1L));
    }

    @Test
    public void expiryOverflowFailsClosed() throws Exception {
        try {
            CallArtifactRetention.expiresAt(Long.MAX_VALUE);
            fail("overflow must not create an immortal artifact");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("overflows"));
        }
    }

    @Test
    public void storedExpiryCannotExtendOrShortenTheImmutableTtl() throws Exception {
        long createdAt = 1_700_000_000_000L;
        long expected = CallArtifactRetention.expiresAt(createdAt);

        assertEquals(expected, CallArtifactRetention.validatedExpiry(
                createdAt, expected));
        assertEquals(CallArtifactRetention.UNREADABLE_EXPIRY,
                CallArtifactRetention.validatedExpiry(createdAt, expected + 1L));
        assertEquals(CallArtifactRetention.UNREADABLE_EXPIRY,
                CallArtifactRetention.validatedExpiry(createdAt, expected - 1L));
        assertEquals(CallArtifactRetention.UNREADABLE_EXPIRY,
                CallArtifactRetention.validatedExpiry(
                        Long.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    public void cleanupDeletesAtBoundaryButNotOneMillisecondEarly() throws Exception {
        File calls = temporary.newFolder("calls");
        File active = session(calls, "active", true);
        File expired = session(calls, "expired", true);
        Map<String, Long> expiries = new HashMap<>();
        expiries.put(active.getName(), 10_001L);
        expiries.put(expired.getName(), 10_000L);
        Set<String> closedBeforeDelete = new HashSet<>();

        CallArtifactRetention.cleanup(calls, 10_000L,
                directory -> expiries.get(directory.getName()),
                directory -> {
                    assertTrue(directory.exists());
                    closedBeforeDelete.add(directory.getName());
                });

        assertTrue(active.isDirectory());
        assertFalse(expired.exists());
        assertEquals(Set.of("expired"), closedBeforeDelete);
    }

    @Test
    public void unreadableSessionIsDeletedAndNestedContentIsRemoved() throws Exception {
        File calls = temporary.newFolder("calls");
        File unreadable = session(calls, "unreadable", true);

        CallArtifactRetention.cleanup(calls, 0L,
                directory -> CallArtifactRetention.UNREADABLE_EXPIRY);

        assertFalse(unreadable.exists());
    }

    @Test
    public void nearestExpiryIgnoresLooseFilesAndEmptyStoreCancelsAlarm()
            throws Exception {
        File calls = temporary.newFolder("calls");
        File later = session(calls, "later", false);
        File sooner = session(calls, "sooner", false);
        File loose = new File(calls, "not-a-session");
        assertTrue(loose.createNewFile());
        Map<String, Long> expiries = new HashMap<>();
        expiries.put(later.getName(), 3_000L);
        expiries.put(sooner.getName(), 2_000L);

        assertEquals(2_000L, CallArtifactRetention.nextExpiry(
                calls, directory -> expiries.get(directory.getName())));

        CallArtifactRetention.cleanup(calls, Long.MAX_VALUE,
                directory -> expiries.get(directory.getName()));
        assertEquals(Long.MAX_VALUE, CallArtifactRetention.nextExpiry(
                calls, directory -> CallArtifactRetention.UNREADABLE_EXPIRY));
    }

    @Test
    public void elapsedAlarmPreservesRemainingDurationAndSaturates() {
        assertEquals(10_100L, CallArtifactRetention.elapsedAlarmTrigger(
                1_000L, 100L, 11_000L));
        assertEquals(100L, CallArtifactRetention.elapsedAlarmTrigger(
                1_000L, 100L, 999L));
        assertEquals(Long.MAX_VALUE, CallArtifactRetention.elapsedAlarmTrigger(
                Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE - 1L));
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
