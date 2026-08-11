package com.aios.callintelligence;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CallRequestIdentityTrackerTest {
    @Test
    public void snapshotsActiveCallIdsWithoutExposingMutableRegistry() {
        CallRequestIdentityTracker tracker = new CallRequestIdentityTracker();
        tracker.tryStart("call-a", new Object(), 2);
        tracker.tryStart("call-b", new Object(), 2);

        java.util.List<String> ids = tracker.callIds();
        ids.clear();

        assertEquals(2, tracker.size());
    }

    @Test
    public void replacementRejectsAndCannotBeRemovedByTheOldIdentity() {
        CallRequestIdentityTracker tracker = new CallRequestIdentityTracker();
        Object oldRequest = new Object();
        Object replacement = new Object();
        assertTrue(tracker.tryStart("same-call", oldRequest, 4));
        assertTrue(tracker.tryStart("same-call", replacement, 4));

        assertFalse(tracker.isCurrent("same-call", oldRequest));
        assertFalse(tracker.finish("same-call", oldRequest));
        assertSame(replacement, tracker.current("same-call"));
        assertTrue(tracker.finish("same-call", replacement));
        assertNull(tracker.current("same-call"));
    }

    @Test
    public void capacityCountsCallIdsButAllowsInPlaceReplacement() {
        CallRequestIdentityTracker tracker = new CallRequestIdentityTracker();
        assertTrue(tracker.tryStart("one", new Object(), 1));
        assertTrue(tracker.tryStart("one", new Object(), 1));
        assertFalse(tracker.tryStart("two", new Object(), 1));
        assertTrue(tracker.contains("one"));
        assertFalse(tracker.contains("two"));
    }

    @Test
    public void nullOrUnrelatedIdentityNeverMatches() {
        CallRequestIdentityTracker tracker = new CallRequestIdentityTracker();
        Object current = new Object();
        tracker.tryStart("call", current, 2);

        assertFalse(tracker.isCurrent("call", null));
        assertFalse(tracker.isCurrent("call", new Object()));
        assertSame(current, tracker.remove("call"));
        assertNull(tracker.remove("call"));
    }
}
