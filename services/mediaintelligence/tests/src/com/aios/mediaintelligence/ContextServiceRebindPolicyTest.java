package com.aios.mediaintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ContextServiceRebindPolicyTest {
    @Test
    public void failuresBackOffAndCapAtOneMinute() {
        ContextServiceRebindPolicy policy = new ContextServiceRebindPolicy();
        long[] expected = {1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L,
                60_000L, 60_000L};
        for (long delay : expected) {
            assertEquals(delay, policy.reserve(false));
            assertTrue(policy.begin());
        }
    }

    @Test
    public void terminalReplacementCanBeImmediateAndUnique() {
        ContextServiceRebindPolicy policy = new ContextServiceRebindPolicy();
        assertEquals(0L, policy.reserve(true));
        assertEquals(ContextServiceRebindPolicy.NO_RETRY, policy.reserve(false));
        assertTrue(policy.begin());
        assertFalse(policy.begin());
    }

    @Test
    public void connectionRacingReservedRetryCancelsThatAttempt() {
        ContextServiceRebindPolicy policy = new ContextServiceRebindPolicy();
        assertEquals(1_000L, policy.reserve(false));
        policy.connected();
        assertFalse(policy.begin());
        assertEquals(1_000L, policy.reserve(false));
    }

    @Test
    public void closeSuppressesReservedAndFutureRetries() {
        ContextServiceRebindPolicy policy = new ContextServiceRebindPolicy();
        assertEquals(1_000L, policy.reserve(false));
        policy.close();
        assertFalse(policy.begin());
        assertEquals(ContextServiceRebindPolicy.NO_RETRY, policy.reserve(true));
    }
}
