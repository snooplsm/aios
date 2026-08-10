package com.aios.callintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Set;

public final class TelecomCallPresenceTrackerTest {
    @Test
    public void multiCallPresenceChangesOnlyAtOuterEdges() {
        TelecomCallPresenceTracker<Object> tracker = new TelecomCallPresenceTracker<>(4, 8);
        Object token = new Object();

        assertTrue(tracker.setPresent(token, 100, "ringing", true));
        assertFalse(tracker.setPresent(token, 100, "active", true));
        assertFalse(tracker.setPresent(token, 100, "ringing", true));
        assertEquals(2, tracker.totalCalls());

        assertFalse(tracker.setPresent(token, 100, "ringing", false));
        assertTrue(tracker.isActive());
        assertTrue(tracker.setPresent(token, 100, "active", false));
        assertFalse(tracker.isActive());
        assertEquals(0, tracker.tokenCount());
    }

    @Test
    public void oneClientDeathDoesNotClearAnotherClientsCall() {
        TelecomCallPresenceTracker<Object> tracker = new TelecomCallPresenceTracker<>(4, 8);
        Object first = new Object();
        Object second = new Object();
        tracker.setPresent(first, 100, "first", true);
        tracker.setPresent(second, 101, "second", true);

        assertFalse(tracker.removeDead(first));
        assertTrue(tracker.isActive());
        assertTrue(tracker.removeDead(second));
        assertFalse(tracker.isActive());
    }

    @Test
    public void deathReportIncludesOnlyCallsWithoutASurvivingUidToken() {
        TelecomCallPresenceTracker<Object> tracker = new TelecomCallPresenceTracker<>(4, 8);
        Object dead = new Object();
        Object survivor = new Object();
        tracker.setPresent(dead, 100, "shared", true);
        tracker.setPresent(dead, 100, "orphaned", true);
        tracker.setPresent(survivor, 100, "shared", true);

        TelecomCallPresenceTracker.DeadClient removed = tracker.removeDeadAndReport(dead);

        assertEquals(Integer.valueOf(100), removed.ownerUid);
        assertEquals(Set.of("orphaned"), removed.orphanedCallIds);
        assertFalse(removed.activityChanged);
        assertTrue(tracker.ownsCall(100, "shared"));
        assertFalse(tracker.ownsCall(100, "orphaned"));

        TelecomCallPresenceTracker.DeadClient finalRemoval =
                tracker.removeDeadAndReport(survivor);
        assertEquals(Set.of("shared"), finalRemoval.orphanedCallIds);
        assertTrue(finalRemoval.activityChanged);
    }

    @Test
    public void absentDeadTokenProducesEmptyIdempotentReport() {
        TelecomCallPresenceTracker<Object> tracker = new TelecomCallPresenceTracker<>(4, 8);

        TelecomCallPresenceTracker.DeadClient removed =
                tracker.removeDeadAndReport(new Object());

        assertNull(removed.ownerUid);
        assertTrue(removed.orphanedCallIds.isEmpty());
        assertFalse(removed.activityChanged);
    }

    @Test
    public void differentUidCannotReuseOrReleaseToken() {
        TelecomCallPresenceTracker<Object> tracker = new TelecomCallPresenceTracker<>(4, 8);
        Object token = new Object();
        tracker.setPresent(token, 100, "call", true);

        assertSecurityException(() -> tracker.setPresent(token, 101, "other", true));
        assertSecurityException(() -> tracker.setPresent(token, 101, "call", false));
        assertEquals(Integer.valueOf(100), tracker.ownerUid(token));
        assertEquals(1, tracker.totalCalls());
    }

    @Test
    public void callOwnershipIsBoundToUidAcrossTokens() {
        TelecomCallPresenceTracker<Object> tracker = new TelecomCallPresenceTracker<>(4, 8);
        Object first = new Object();
        Object sameUid = new Object();
        Object otherUid = new Object();
        tracker.setPresent(first, 100, "call", true);

        assertTrue(tracker.ownsCall(100, "call"));
        assertFalse(tracker.ownsCall(101, "call"));
        assertFalse(tracker.ownsCall(100, "missing"));
        assertFalse(tracker.setPresent(sameUid, 100, "call", true));
        assertEquals(2, tracker.totalCalls());

        assertSecurityException(() -> tracker.setPresent(otherUid, 101, "call", true));
        assertEquals(2, tracker.totalCalls());
        assertEquals(2, tracker.tokenCount());
    }

    @Test
    public void tokenCallSnapshotCannotMutateTrackerState() {
        TelecomCallPresenceTracker<Object> tracker = new TelecomCallPresenceTracker<>(4, 8);
        Object token = new Object();
        tracker.setPresent(token, 100, "one", true);
        tracker.setPresent(token, 100, "two", true);

        assertEquals(Set.of("one", "two"), tracker.callIds(token));
        assertUnsupportedOperation(() -> tracker.callIds(token).remove("one"));
        assertTrue(tracker.ownsCall(100, "one"));
        assertTrue(tracker.ownsCall(100, "two"));
    }

    @Test
    public void perTokenBoundRejectsAdditionalCallWithoutLosingState() {
        TelecomCallPresenceTracker<Object> tracker = new TelecomCallPresenceTracker<>(4, 2);
        Object token = new Object();
        tracker.setPresent(token, 100, "one", true);
        tracker.setPresent(token, 100, "two", true);

        try {
            tracker.setPresent(token, 100, "three", true);
            fail("expected bounded-call rejection");
        } catch (IllegalStateException expected) {
            // Expected.
        }
        assertEquals(2, tracker.totalCalls());
        assertEquals(1, tracker.tokenCount());
    }

    @Test
    public void tokenBoundRejectsAdditionalClientWithoutLosingState() {
        TelecomCallPresenceTracker<Object> tracker = new TelecomCallPresenceTracker<>(2, 8);
        tracker.setPresent(new Object(), 100, "one", true);
        tracker.setPresent(new Object(), 101, "two", true);

        try {
            tracker.setPresent(new Object(), 102, "three", true);
            fail("expected bounded-token rejection");
        } catch (IllegalStateException expected) {
            // Expected.
        }
        assertEquals(2, tracker.totalCalls());
        assertEquals(2, tracker.tokenCount());
    }

    @Test
    public void absentReleaseAndDeadTokenAreIdempotent() {
        TelecomCallPresenceTracker<Object> tracker = new TelecomCallPresenceTracker<>(4, 8);
        Object token = new Object();

        assertFalse(tracker.setPresent(token, 100, "missing", false));
        assertFalse(tracker.removeDead(token));
        assertNull(tracker.ownerUid(token));
    }

    private static void assertSecurityException(Runnable action) {
        try {
            action.run();
            fail("expected SecurityException");
        } catch (SecurityException expected) {
            // Expected.
        }
    }

    private static void assertUnsupportedOperation(Runnable action) {
        try {
            action.run();
            fail("expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }
}
