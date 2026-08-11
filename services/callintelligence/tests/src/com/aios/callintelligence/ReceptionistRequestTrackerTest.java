package com.aios.callintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ReceptionistRequestTrackerTest {
    @Test
    public void recoveryPreservesDeadlineAndInvalidatesOldCallbacks() {
        ReceptionistRequestTracker tracker = new ReceptionistRequestTracker();
        ReceptionistRequestTracker.Token first = tracker.begin(1_000L, 15_000L);
        ReceptionistRequestTracker.Token recovered = tracker.recover(first, 4_000L);

        assertNotNull(recovered);
        assertEquals(16_000L, recovered.deadlineElapsedRealtimeMillis);
        assertTrue(recovered.generation > first.generation);
        assertFalse(tracker.isCurrent(first));
        assertTrue(tracker.isCurrent(recovered));
        assertFalse(tracker.complete(first));
        assertTrue(tracker.complete(recovered));
        assertFalse(tracker.isActive());
    }

    @Test
    public void repeatedRecoveryNeverRenewsTheBudget() {
        ReceptionistRequestTracker tracker = new ReceptionistRequestTracker();
        ReceptionistRequestTracker.Token first = tracker.begin(5_000L, 15_000L);
        ReceptionistRequestTracker.Token second = tracker.recover(first, 10_000L);
        ReceptionistRequestTracker.Token third = tracker.recover(second, 19_999L);

        assertEquals(20_000L, second.deadlineElapsedRealtimeMillis);
        assertEquals(20_000L, third.deadlineElapsedRealtimeMillis);
    }

    @Test
    public void recoveryAtDeadlineExpiresTheRequest() {
        ReceptionistRequestTracker tracker = new ReceptionistRequestTracker();
        ReceptionistRequestTracker.Token first = tracker.begin(1_000L, 15_000L);

        assertNull(tracker.recover(first, 16_000L));
        assertFalse(tracker.isActive());
        assertFalse(tracker.complete(first));
    }

    @Test
    public void onlyOneSemanticRequestCanBeActive() {
        ReceptionistRequestTracker tracker = new ReceptionistRequestTracker();
        ReceptionistRequestTracker.Token first = tracker.begin(1_000L, 15_000L);

        assertNotNull(first);
        assertNull(tracker.begin(2_000L, 15_000L));
        assertTrue(tracker.complete(first));
        assertNotNull(tracker.begin(3_000L, 15_000L));
    }

    @Test
    public void closeRejectsLateCompletionAndNewWork() {
        ReceptionistRequestTracker tracker = new ReceptionistRequestTracker();
        ReceptionistRequestTracker.Token first = tracker.begin(1_000L, 15_000L);

        tracker.close();

        assertFalse(tracker.complete(first));
        assertFalse(tracker.isActive());
        assertNull(tracker.begin(2_000L, 15_000L));
        assertNull(tracker.recover(first, 2_000L));
    }
}
