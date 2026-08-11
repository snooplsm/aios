package com.aios.modelbroker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;

import org.junit.Test;

public final class SessionDeadlineQueueTest {
    @Test
    public void expiresAtExactBoundaryInDeadlineOrder() {
        SessionDeadlineQueue queue = new SessionDeadlineQueue();
        queue.add(30L, 3_000L);
        queue.add(10L, 1_000L);
        queue.add(20L, 1_000L);

        assertEquals(List.of(10L, 20L), queue.removeExpired(1_000L));
        assertEquals(1, queue.size());
        assertEquals(2_000L, queue.millisUntilNext(1_000L));
    }

    @Test
    public void futureDeadlineDoesNotExpireEarly() {
        SessionDeadlineQueue queue = new SessionDeadlineQueue();
        queue.add(1L, 2_000L);

        assertTrue(queue.removeExpired(1_999L).isEmpty());
        assertEquals(1L, queue.millisUntilNext(1_999L));
    }

    @Test
    public void removedDeadlineNeverReappears() {
        SessionDeadlineQueue queue = new SessionDeadlineQueue();
        queue.add(1L, 1_000L);
        queue.add(2L, 2_000L);

        assertTrue(queue.remove(1L));
        assertFalse(queue.remove(1L));
        assertEquals(1_000L, queue.millisUntilNext(1_000L));
        assertEquals(List.of(2L), queue.removeExpired(2_000L));
    }

    @Test
    public void emptyQueueHasNoWakeup() {
        SessionDeadlineQueue queue = new SessionDeadlineQueue();

        assertEquals(Long.MAX_VALUE, queue.millisUntilNext(Long.MAX_VALUE));
        assertTrue(queue.removeExpired(Long.MAX_VALUE).isEmpty());
    }

    @Test
    public void maxDeadlineDoesNotOverflowDelay() {
        SessionDeadlineQueue queue = new SessionDeadlineQueue();
        queue.add(1L, Long.MAX_VALUE);

        assertEquals(Long.MAX_VALUE - 10L, queue.millisUntilNext(10L));
        assertEquals(List.of(1L), queue.removeExpired(Long.MAX_VALUE));
    }

    @Test
    public void invalidOrDuplicateEntryIsRejected() {
        SessionDeadlineQueue queue = new SessionDeadlineQueue();

        assertIllegalArgument(() -> queue.add(0L, 1L));
        assertIllegalArgument(() -> queue.add(1L, 0L));
        queue.add(1L, 1L);
        assertIllegalArgument(() -> queue.add(1L, 2L));
    }

    @Test
    public void clearDropsLiveAndStaleEntries() {
        SessionDeadlineQueue queue = new SessionDeadlineQueue();
        queue.add(1L, 1L);
        queue.add(2L, 2L);
        queue.remove(1L);

        queue.clear();

        assertEquals(0, queue.size());
        assertEquals(Long.MAX_VALUE, queue.millisUntilNext(0L));
    }

    private static void assertIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
