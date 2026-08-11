package com.aios.callintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ServiceRebindPolicyTest {
    @Test
    public void terminalDeathCanReserveImmediateReplacement() {
        ServiceRebindPolicy policy = new ServiceRebindPolicy();
        assertEquals(0L, policy.reserve(true));
        assertTrue(policy.begin());
    }

    @Test
    public void failuresBackOffAndCapAtOneMinute() {
        ServiceRebindPolicy policy = new ServiceRebindPolicy();
        long[] expected = {1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L,
                60_000L, 60_000L};
        for (long delay : expected) {
            assertEquals(delay, policy.reserve(false));
            assertTrue(policy.begin());
        }
    }

    @Test
    public void onlyOneRetryCanBeScheduled() {
        ServiceRebindPolicy policy = new ServiceRebindPolicy();
        assertEquals(1_000L, policy.reserve(false));
        assertEquals(ServiceRebindPolicy.NO_RETRY, policy.reserve(true));
        assertTrue(policy.begin());
        assertFalse(policy.begin());
    }

    @Test
    public void successResetsBackoff() {
        ServiceRebindPolicy policy = new ServiceRebindPolicy();
        assertEquals(1_000L, policy.reserve(false));
        assertTrue(policy.begin());
        assertEquals(2_000L, policy.reserve(false));
        assertTrue(policy.begin());
        policy.connected();
        assertEquals(1_000L, policy.reserve(false));
    }

    @Test
    public void connectionRacingReservedRetryCancelsThatAttempt() {
        ServiceRebindPolicy policy = new ServiceRebindPolicy();
        assertEquals(1_000L, policy.reserve(false));
        policy.connected();
        assertFalse(policy.begin());
        assertEquals(1_000L, policy.reserve(false));
        assertTrue(policy.begin());
    }

    @Test
    public void closeSuppressesReservedAndFutureRetries() {
        ServiceRebindPolicy policy = new ServiceRebindPolicy();
        assertEquals(1_000L, policy.reserve(false));
        policy.close();
        assertFalse(policy.begin());
        assertEquals(ServiceRebindPolicy.NO_RETRY, policy.reserve(true));
    }
}
