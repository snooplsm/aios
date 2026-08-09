package com.aios.modelbroker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class CallActivityLeaseTrackerTest {
    @Test
    public void firstAndFinalLeaseProduceStateTransitions() {
        CallActivityLeaseTracker<Object> tracker = new CallActivityLeaseTracker<>();
        Object first = new Object();
        Object second = new Object();

        assertTrue(tracker.acquire(first, 100));
        assertFalse(tracker.acquire(second, 100));
        assertTrue(tracker.isActive());
        assertEquals(2, tracker.size());

        assertFalse(tracker.release(first, 100));
        assertTrue(tracker.isActive());
        assertTrue(tracker.release(second, 100));
        assertFalse(tracker.isActive());
    }

    @Test
    public void duplicateAcquisitionIsIdempotentForOwner() {
        CallActivityLeaseTracker<Object> tracker = new CallActivityLeaseTracker<>();
        Object token = new Object();

        assertTrue(tracker.acquire(token, 100));
        assertFalse(tracker.acquire(token, 100));
        assertEquals(1, tracker.size());
        assertTrue(tracker.release(token, 100));
        assertFalse(tracker.release(token, 100));
    }

    @Test
    public void tokenDeathClearsOnlyItsOwnLease() {
        CallActivityLeaseTracker<Object> tracker = new CallActivityLeaseTracker<>();
        Object first = new Object();
        Object second = new Object();
        tracker.acquire(first, 100);
        tracker.acquire(second, 100);

        assertFalse(tracker.removeDead(first));
        assertTrue(tracker.isActive());
        assertTrue(tracker.removeDead(second));
        assertFalse(tracker.isActive());
        assertFalse(tracker.removeDead(second));
    }

    @Test
    public void differentUidCannotReuseOrReleaseToken() {
        CallActivityLeaseTracker<Object> tracker = new CallActivityLeaseTracker<>();
        Object token = new Object();
        tracker.acquire(token, 100);

        assertSecurityException(() -> tracker.acquire(token, 101));
        assertSecurityException(() -> tracker.release(token, 101));
        assertEquals(Integer.valueOf(100), tracker.ownerUid(token));
        assertTrue(tracker.isActive());
    }

    @Test
    public void clearReportsPriorStateAndDropsEveryLease() {
        CallActivityLeaseTracker<Object> tracker = new CallActivityLeaseTracker<>();
        assertFalse(tracker.clear());
        tracker.acquire(new Object(), 100);
        tracker.acquire(new Object(), 101);

        assertTrue(tracker.clear());
        assertFalse(tracker.isActive());
        assertEquals(0, tracker.size());
        assertFalse(tracker.clear());
    }

    private static void assertSecurityException(Runnable action) {
        try {
            action.run();
            fail("expected SecurityException");
        } catch (SecurityException expected) {
            // Expected.
        }
    }
}
