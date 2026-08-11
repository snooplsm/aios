package com.aios.modelbroker;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class SessionDeadlinePolicyTest {
    @Test
    public void finiteStreamingAsrIsTracked() {
        assertTrue(SessionDeadlinePolicy.validMode("streaming_asr", 30_000L));
        assertTrue(SessionDeadlinePolicy.validAt("streaming_asr", 30_000L, 1L));
        assertTrue(SessionDeadlinePolicy.shouldTrack("streaming_asr", 30_000L));
    }

    @Test
    public void lifecycleBoundStreamingAsrIsNotTracked() {
        assertTrue(SessionDeadlinePolicy.validMode(
                "streaming_asr", SessionDeadlinePolicy.LIFECYCLE_BOUND));
        assertTrue(SessionDeadlinePolicy.validAt(
                "streaming_asr", SessionDeadlinePolicy.LIFECYCLE_BOUND, 1L));
        assertFalse(SessionDeadlinePolicy.shouldTrack(
                "streaming_asr", SessionDeadlinePolicy.LIFECYCLE_BOUND));
    }

    @Test
    public void finiteGenerationIsTracked() {
        assertTrue(SessionDeadlinePolicy.validMode("text_generation", 30_000L));
        assertTrue(SessionDeadlinePolicy.validAt("text_generation", 30_000L, 1L));
        assertTrue(SessionDeadlinePolicy.shouldTrack("text_generation", 30_000L));
    }

    @Test
    public void finiteCapabilityCannotDisableItsDeadline() {
        assertFalse(SessionDeadlinePolicy.validMode(
                "text_generation", SessionDeadlinePolicy.LIFECYCLE_BOUND));
        assertFalse(SessionDeadlinePolicy.validAt(
                "text_generation", SessionDeadlinePolicy.LIFECYCLE_BOUND, 1L));
        try {
            SessionDeadlinePolicy.shouldTrack(
                    "text_generation", SessionDeadlinePolicy.LIFECYCLE_BOUND);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    @Test
    public void unknownCapabilityFailsClosedForLifecycleMode() {
        assertFalse(SessionDeadlinePolicy.validMode(
                "future_capability", SessionDeadlinePolicy.LIFECYCLE_BOUND));
    }

    @Test
    public void expiredFiniteDeadlineIsRejected() {
        assertFalse(SessionDeadlinePolicy.validAt("text_generation", 1_000L, 1_000L));
        assertFalse(SessionDeadlinePolicy.validAt("text_generation", 999L, 1_000L));
    }

    @Test
    public void finiteHorizonHasAnExactUpperBound() {
        long now = 1_000L;
        assertTrue(SessionDeadlinePolicy.validAt(
                "text_generation",
                now + SessionDeadlinePolicy.MAX_FINITE_HORIZON_MILLIS,
                now));
        assertFalse(SessionDeadlinePolicy.validAt(
                "text_generation",
                now + SessionDeadlinePolicy.MAX_FINITE_HORIZON_MILLIS + 1L,
                now));
    }

    @Test
    public void negativeElapsedRealtimeFailsClosed() {
        assertFalse(SessionDeadlinePolicy.validAt("text_generation", 1L, -1L));
    }
}
