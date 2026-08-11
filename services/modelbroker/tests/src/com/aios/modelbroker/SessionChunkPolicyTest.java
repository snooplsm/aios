package com.aios.modelbroker;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SessionChunkPolicyTest {
    @Test
    public void boundedSessionAcceptsExactCharacterLimit() {
        assertTrue(SessionChunkPolicy.accepts(
                "call_agent",
                false,
                SessionChunkPolicy.MAX_BOUNDED_CHUNKS - 1L,
                SessionChunkPolicy.MAX_BOUNDED_CHARS - 1L,
                1,
                0L,
                0L));
    }

    @Test
    public void boundedSessionRejectsChunkOrCharacterOverflow() {
        assertFalse(SessionChunkPolicy.accepts(
                "call_agent",
                false,
                SessionChunkPolicy.MAX_BOUNDED_CHUNKS,
                0L,
                0,
                0L,
                0L));
        assertFalse(SessionChunkPolicy.accepts(
                "media_background",
                true,
                0L,
                SessionChunkPolicy.MAX_BOUNDED_CHARS,
                1,
                0L,
                0L));
    }

    @Test
    public void lifecycleMediaRemainsBounded() {
        assertFalse(SessionChunkPolicy.accepts(
                "media_background",
                true,
                SessionChunkPolicy.MAX_BOUNDED_CHUNKS,
                0L,
                0,
                86_400_000L,
                0L));
    }

    @Test
    public void lifecycleCallBudgetGrowsWithSourceTimeline() {
        assertTrue(SessionChunkPolicy.accepts(
                "call_rx", true, 63L, 100_000_000L, 1, 0L, 0L));
        assertFalse(SessionChunkPolicy.accepts(
                "call_rx", true, 64L, 0L, 1, 0L, 0L));
        assertTrue(SessionChunkPolicy.accepts(
                "call_tx", true, 163L, 0L, 1, 10_000L, 10_000L));
    }

    @Test
    public void lifecycleCallRejectsImplausibleFutureTimeline() {
        assertTrue(SessionChunkPolicy.accepts(
                "call_rx", true, 0L, 0L, 1, 11_000L, 1_000L));
        assertFalse(SessionChunkPolicy.accepts(
                "call_rx", true, 0L, 0L, 1, 11_001L, 1_000L));
    }

    @Test
    public void lifecycleCallHasNoCumulativeTextCeiling() {
        assertTrue(SessionChunkPolicy.accepts(
                "call_rx",
                true,
                100L,
                Long.MAX_VALUE,
                Integer.MAX_VALUE,
                10_000L,
                10_000L));
    }

    @Test
    public void invalidCountersFailClosed() {
        assertFalse(SessionChunkPolicy.accepts(
                "call_rx", true, -1L, 0L, 0, 0L, 0L));
        assertFalse(SessionChunkPolicy.accepts(
                "call_rx", true, 0L, -1L, 0, 0L, 0L));
        assertFalse(SessionChunkPolicy.accepts(
                "call_rx", true, 0L, 0L, -1, 0L, 0L));
    }
}
