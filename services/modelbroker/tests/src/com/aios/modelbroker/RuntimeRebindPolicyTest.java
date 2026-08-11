package com.aios.modelbroker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RuntimeRebindPolicyTest {
    @Test
    public void terminalBindingDeathCanReserveImmediateRebind() {
        RuntimeRebindPolicy policy = new RuntimeRebindPolicy();

        assertEquals(0L, policy.reserve(true));
        assertTrue(policy.begin());
    }

    @Test
    public void failedBindsBackOffAndCapAtOneMinute() {
        RuntimeRebindPolicy policy = new RuntimeRebindPolicy();

        long[] expected = {1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L,
                60_000L, 60_000L};
        for (long delay : expected) {
            assertEquals(delay, policy.reserve(false));
            assertTrue(policy.begin());
        }
    }

    @Test
    public void onlyOneRetryMayBeScheduled() {
        RuntimeRebindPolicy policy = new RuntimeRebindPolicy();

        assertEquals(1_000L, policy.reserve(false));
        assertEquals(RuntimeRebindPolicy.NO_RETRY, policy.reserve(true));
        assertTrue(policy.begin());
        assertFalse(policy.begin());
    }

    @Test
    public void successfulConnectionResetsBackoff() {
        RuntimeRebindPolicy policy = new RuntimeRebindPolicy();
        assertEquals(1_000L, policy.reserve(false));
        assertTrue(policy.begin());
        assertEquals(2_000L, policy.reserve(false));
        assertTrue(policy.begin());

        policy.connected();

        assertEquals(1_000L, policy.reserve(false));
    }

    @Test
    public void closePermanentlySuppressesRetries() {
        RuntimeRebindPolicy policy = new RuntimeRebindPolicy();
        assertEquals(1_000L, policy.reserve(false));

        policy.close();

        assertFalse(policy.begin());
        assertEquals(RuntimeRebindPolicy.NO_RETRY, policy.reserve(true));
    }
}
