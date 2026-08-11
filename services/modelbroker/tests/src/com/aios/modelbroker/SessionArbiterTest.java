package com.aios.modelbroker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class SessionArbiterTest {
    @Test
    public void callRxCancelsAllMedia() {
        SessionArbiter arbiter = arbiter(2);
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
        SessionArbiter arbiter = arbiter(1);
        arbiter.setCallActive(true);

        SessionArbiter.Change media =
                arbiter.submit(1L, 100, WorkClass.MEDIA_BACKGROUND, 1);
        assertEquals(SessionArbiter.Status.QUEUED, media.submittedStatus);

        SessionArbiter.Change cleared = arbiter.setCallActive(false);
        assertEquals(1, cleared.activated.size());
        assertEquals(Long.valueOf(1L), cleared.activated.get(0));
    }

    @Test
    public void completedCallWorkDoesNotLeaveStickyMediaGate() {
        SessionArbiter arbiter = arbiter(1);
        arbiter.submit(1L, 100, WorkClass.CALL_AGENT, 2);
        SessionArbiter.Change media =
                arbiter.submit(2L, 200, WorkClass.MEDIA_BACKGROUND, 1);
        assertEquals(SessionArbiter.Status.QUEUED, media.submittedStatus);

        SessionArbiter.Change finished = arbiter.finish(1L, 100);

        assertEquals(1, finished.activated.size());
        assertEquals(Long.valueOf(2L), finished.activated.get(0));
    }

    @Test
    public void explicitLeaseStillBlocksMediaAfterCallWorkCompletes() {
        SessionArbiter arbiter = arbiter(1);
        arbiter.setCallActive(true);
        arbiter.submit(1L, 100, WorkClass.CALL_RX, 2);
        arbiter.submit(2L, 200, WorkClass.MEDIA_BACKGROUND, 1);

        SessionArbiter.Change finished = arbiter.finish(1L, 100);
        assertTrue(finished.activated.isEmpty());

        SessionArbiter.Change released = arbiter.setCallActive(false);
        assertEquals(1, released.activated.size());
        assertEquals(Long.valueOf(2L), released.activated.get(0));
    }

    @Test
    public void ownerQuotaCountsQueuedAndActiveSessions() {
        SessionArbiter arbiter = arbiter(1);
        arbiter.submit(1L, 100, WorkClass.CALL_RX, 2);
        arbiter.submit(2L, 100, WorkClass.CALL_TX, 2);

        SessionArbiter.Change third =
                arbiter.submit(3L, 100, WorkClass.CALL_AGENT, 2);

        assertEquals(SessionArbiter.Status.REJECTED_QUOTA, third.submittedStatus);
        assertEquals(2, arbiter.size());
    }

    @Test
    public void differentUidCannotFinishSession() {
        SessionArbiter arbiter = arbiter(1);
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
        SessionArbiter arbiter = arbiter(1);
        arbiter.submit(1L, 100, WorkClass.CALL_RX, 3);
        arbiter.submit(2L, 100, WorkClass.CALL_AGENT, 3);
        arbiter.submit(3L, 100, WorkClass.CALL_TX, 3);

        SessionArbiter.Change finished = arbiter.finish(1L, 100);

        assertEquals(1, finished.activated.size());
        assertEquals(Long.valueOf(3L), finished.activated.get(0));
    }

    @Test
    public void memoryPressurePreemptsOnlyBackgroundWork() {
        SessionArbiter arbiter = arbiter(3);
        arbiter.submit(1L, 100, WorkClass.CALL_RX, 3);
        arbiter.submit(2L, 200, WorkClass.MEDIA_BACKGROUND, 3);

        SessionArbiter.Change pressure = arbiter.preemptBackgroundForMemoryPressure();

        assertEquals(1, pressure.cancelled.size());
        assertEquals(Long.valueOf(2L), pressure.cancelled.get(0));
        assertTrue(arbiter.isOwner(1L, 100));
        assertEquals(1, arbiter.size());
    }

    @Test
    public void rxAndTxShareTwoStreamCapacity() {
        SessionArbiter arbiter = new SessionArbiter(
                new SessionCapacityPolicy(3, 2, 1));

        assertEquals(SessionArbiter.Status.ACTIVE,
                arbiter.submit(1L, 100, WorkClass.CALL_RX, 4).submittedStatus);
        assertEquals(SessionArbiter.Status.ACTIVE,
                arbiter.submit(2L, 100, WorkClass.CALL_TX, 4).submittedStatus);
        assertEquals(SessionArbiter.Status.QUEUED,
                arbiter.submit(3L, 100, WorkClass.CALL_TX, 4).submittedStatus);
        assertEquals(SessionArbiter.Status.ACTIVE,
                arbiter.submit(4L, 100, WorkClass.CALL_AGENT, 4).submittedStatus);
    }

    @Test
    public void secondAgentQueuesWhileAsrCanUseFreeCapacity() {
        SessionArbiter arbiter = new SessionArbiter(
                new SessionCapacityPolicy(3, 2, 1));

        assertEquals(SessionArbiter.Status.ACTIVE,
                arbiter.submit(1L, 100, WorkClass.CALL_AGENT, 3).submittedStatus);
        assertEquals(SessionArbiter.Status.QUEUED,
                arbiter.submit(2L, 100, WorkClass.CALL_AGENT, 3).submittedStatus);
        assertEquals(SessionArbiter.Status.ACTIVE,
                arbiter.submit(3L, 100, WorkClass.CALL_RX, 3).submittedStatus);
    }

    @Test
    public void rxPreemptsTxWhenSharedPoolIsFull() {
        SessionArbiter arbiter = new SessionArbiter(
                new SessionCapacityPolicy(3, 1, 1));
        assertEquals(SessionArbiter.Status.ACTIVE,
                arbiter.submit(1L, 100, WorkClass.CALL_TX, 2).submittedStatus);

        SessionArbiter.Change rx =
                arbiter.submit(2L, 100, WorkClass.CALL_RX, 2);

        assertEquals(SessionArbiter.Status.ACTIVE, rx.submittedStatus);
        assertEquals(1, rx.cancelled.size());
        assertEquals(Long.valueOf(1L), rx.cancelled.get(0));
        assertTrue(arbiter.isOwner(2L, 100));
        assertEquals(1, arbiter.size());
    }

    @Test
    public void txCannotDisplaceRxFromSharedPool() {
        SessionArbiter arbiter = new SessionArbiter(
                new SessionCapacityPolicy(3, 1, 1));
        arbiter.submit(1L, 100, WorkClass.CALL_RX, 2);

        SessionArbiter.Change tx =
                arbiter.submit(2L, 100, WorkClass.CALL_TX, 2);

        assertEquals(SessionArbiter.Status.QUEUED, tx.submittedStatus);
        assertTrue(tx.cancelled.isEmpty());
        assertTrue(arbiter.isOwner(1L, 100));
    }

    @Test
    public void promotionSkipsSaturatedClassWithoutBreakingPriorityOrder() {
        SessionArbiter arbiter = new SessionArbiter(
                new SessionCapacityPolicy(3, 1, 1));
        arbiter.submit(1L, 100, WorkClass.CALL_RX, 4);
        arbiter.submit(2L, 100, WorkClass.CALL_AGENT, 4);
        arbiter.submit(3L, 100, WorkClass.CALL_RX, 4);
        arbiter.submit(4L, 100, WorkClass.CALL_AGENT, 4);

        SessionArbiter.Change finished = arbiter.finish(2L, 100);

        assertEquals(1, finished.activated.size());
        assertEquals(Long.valueOf(4L), finished.activated.get(0));
        assertTrue(arbiter.isOwner(3L, 100));
    }

    private static SessionArbiter arbiter(int capacity) {
        return new SessionArbiter(
                new SessionCapacityPolicy(capacity, capacity, capacity));
    }
}
