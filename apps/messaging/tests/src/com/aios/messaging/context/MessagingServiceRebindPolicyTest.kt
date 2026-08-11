package com.aios.messaging.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagingServiceRebindPolicyTest {
    @Test
    fun terminalReplacementCanBeImmediateAndUnique() {
        val policy = MessagingServiceRebindPolicy()

        assertEquals(0L, policy.reserve(immediate = true))
        assertEquals(MessagingServiceRebindPolicy.NO_RETRY, policy.reserve(immediate = false))
        assertTrue(policy.begin())
        assertFalse(policy.begin())
    }

    @Test
    fun failedBindsBackOffAndCapAtOneMinute() {
        val policy = MessagingServiceRebindPolicy()

        listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L)
            .forEach { expected ->
                assertEquals(expected, policy.reserve(immediate = false))
                assertTrue(policy.begin())
            }
    }

    @Test
    fun onlyOneRetryCanBeScheduled() {
        val policy = MessagingServiceRebindPolicy()
        assertEquals(1_000L, policy.reserve(immediate = false))

        assertEquals(MessagingServiceRebindPolicy.NO_RETRY, policy.reserve(immediate = false))
        assertTrue(policy.begin())
        assertFalse(policy.begin())
    }

    @Test
    fun successfulConnectionResetsBackoff() {
        val policy = MessagingServiceRebindPolicy()
        assertEquals(1_000L, policy.reserve(immediate = false))
        assertTrue(policy.begin())
        assertEquals(2_000L, policy.reserve(immediate = false))
        assertTrue(policy.begin())

        policy.connected()

        assertEquals(1_000L, policy.reserve(immediate = false))
    }

    @Test
    fun connectionRacingReservedRetryCancelsThatAttempt() {
        val policy = MessagingServiceRebindPolicy()
        assertEquals(1_000L, policy.reserve(immediate = false))
        policy.connected()

        assertFalse(policy.begin())
        assertEquals(1_000L, policy.reserve(immediate = false))
    }

    @Test
    fun closePermanentlySuppressesRetry() {
        val policy = MessagingServiceRebindPolicy()
        assertEquals(1_000L, policy.reserve(immediate = false))

        policy.close()

        assertFalse(policy.begin())
        assertEquals(MessagingServiceRebindPolicy.NO_RETRY, policy.reserve(immediate = true))
    }
}
