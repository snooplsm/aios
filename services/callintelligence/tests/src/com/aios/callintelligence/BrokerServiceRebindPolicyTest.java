package com.aios.callintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BrokerServiceRebindPolicyTest {
    @Test
    public void terminalDeathCanReserveImmediateReplacement() {
        BrokerServiceRebindPolicy policy = new BrokerServiceRebindPolicy();
        assertEquals(0L, policy.reserve(true));
        assertTrue(policy.begin());
    }

    @Test
    public void failuresBackOffAndCapAtOneMinute() {
        BrokerServiceRebindPolicy policy = new BrokerServiceRebindPolicy();
        long[] expected = {1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L,
                60_000L, 60_000L};
        for (long delay : expected) {
            assertEquals(delay, policy.reserve(false));
            assertTrue(policy.begin());
        }
    }

    @Test
    public void onlyOneRetryCanBeScheduled() {
        BrokerServiceRebindPolicy policy = new BrokerServiceRebindPolicy();
        assertEquals(1_000L, policy.reserve(false));
        assertEquals(BrokerServiceRebindPolicy.NO_RETRY, policy.reserve(true));
        assertTrue(policy.begin());
        assertFalse(policy.begin());
    }

    @Test
    public void successResetsBackoff() {
        BrokerServiceRebindPolicy policy = new BrokerServiceRebindPolicy();
        assertEquals(1_000L, policy.reserve(false));
        assertTrue(policy.begin());
        assertEquals(2_000L, policy.reserve(false));
        assertTrue(policy.begin());
        policy.connected();
        assertEquals(1_000L, policy.reserve(false));
    }

    @Test
    public void closeSuppressesReservedAndFutureRetries() {
        BrokerServiceRebindPolicy policy = new BrokerServiceRebindPolicy();
        assertEquals(1_000L, policy.reserve(false));
        policy.close();
        assertFalse(policy.begin());
        assertEquals(BrokerServiceRebindPolicy.NO_RETRY, policy.reserve(true));
    }
}
