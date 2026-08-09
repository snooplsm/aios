package com.aios.modelbroker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class SessionArbiterTest {
    @Test
    public void callRxCancelsAllMedia() {
        SessionArbiter arbiter = new SessionArbiter(2);
        assertEquals(SessionArbiter.Status.ACTIVE,
                arbiter.submit(1L, 100, WorkClass.MEDIA_BACKGROUND, 2).submittedStatus);
        assertEquals(SessionArbiter.Status.ACTIVE,
                arbiter.submit(2L, 100, WorkClass.MEDIA_BACKGROUND, 2).submittedStatus);

        SessionArbiter.Change call =
                arbiter.submit(3L, 200, WorkClass.CALL_RX, 3);

        assertEquals(SessionArbiter.Status.ACTIVE, call.submittedStatus);
        assertEquals(2, call.cancelled.size());
        assertTrue(call.cancelled.contains(1L));
        assertTrue(call.cancelled.contains(2L));
        assertEquals(1, arbiter.size());
    }

    @Test
    public void explicitCallGateQueuesMediaUntilCleared() {
        SessionArbiter arbiter = new SessionArbiter(1);
        arbiter.setCallActive(true);

        SessionArbiter.Change media =
                arbiter.submit(1L, 100, WorkClass.MEDIA_BACKGROUND, 1);
        assertEquals(SessionArbiter.Status.QUEUED, media.submittedStatus);

        SessionArbiter.Change cleared = arbiter.setCallActive(false);
        assertEquals(1, cleared.activated.size());
        assertEquals(Long.valueOf(1L), cleared.activated.get(0));
    }

    @Test
    public void ownerQuotaCountsQueuedAndActiveSessions() {
        SessionArbiter arbiter = new SessionArbiter(1);
        arbiter.submit(1L, 100, WorkClass.CALL_RX, 2);
        arbiter.submit(2L, 100, WorkClass.CALL_TX, 2);

        SessionArbiter.Change third =
                arbiter.submit(3L, 100, WorkClass.CALL_AGENT, 2);

        assertEquals(SessionArbiter.Status.REJECTED_QUOTA, third.submittedStatus);
        assertEquals(2, arbiter.size());
    }

    @Test
    public void differentUidCannotFinishSession() {
        SessionArbiter arbiter = new SessionArbiter(1);
        arbiter.submit(1L, 100, WorkClass.CALL_RX, 1);
        try {
            arbiter.finish(1L, 101);
            fail("cross-UID finish should throw");
        } catch (SecurityException expected) {
            // Expected.
        }
        assertTrue(arbiter.isOwner(1L, 100));
    }

    @Test
    public void highestPriorityQueuedSessionPromotesFirst() {
        SessionArbiter arbiter = new SessionArbiter(1);
        arbiter.submit(1L, 100, WorkClass.CALL_RX, 3);
        arbiter.submit(2L, 100, WorkClass.CALL_AGENT, 3);
        arbiter.submit(3L, 100, WorkClass.CALL_TX, 3);

        SessionArbiter.Change finished = arbiter.finish(1L, 100);

        assertEquals(1, finished.activated.size());
        assertEquals(Long.valueOf(3L), finished.activated.get(0));
    }

    @Test
    public void memoryPressurePreemptsOnlyBackgroundWork() {
        SessionArbiter arbiter = new SessionArbiter(3);
        arbiter.submit(1L, 100, WorkClass.CALL_RX, 3);
        arbiter.submit(2L, 200, WorkClass.MEDIA_BACKGROUND, 3);

        SessionArbiter.Change pressure = arbiter.preemptBackgroundForMemoryPressure();

        assertEquals(1, pressure.cancelled.size());
        assertEquals(Long.valueOf(2L), pressure.cancelled.get(0));
        assertTrue(arbiter.isOwner(1L, 100));
        assertEquals(1, arbiter.size());
    }
}
