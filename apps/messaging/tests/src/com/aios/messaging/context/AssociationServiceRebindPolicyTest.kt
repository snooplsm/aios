package com.aios.messaging.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssociationServiceRebindPolicyTest {
    @Test
    fun failuresBackOffAndCapAtOneMinute() {
        val policy = AssociationServiceRebindPolicy()
        listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L)
            .forEach { delay ->
                assertEquals(delay, policy.reserve(immediate = false))
                assertTrue(policy.begin())
            }
    }

    @Test
    fun terminalReplacementCanBeImmediateAndUnique() {
        val policy = AssociationServiceRebindPolicy()
        assertEquals(0L, policy.reserve(immediate = true))
        assertEquals(AssociationServiceRebindPolicy.NO_RETRY, policy.reserve(immediate = false))
        assertTrue(policy.begin())
        assertFalse(policy.begin())
    }

    @Test
    fun connectionRacingReservedRetryCancelsThatAttempt() {
        val policy = AssociationServiceRebindPolicy()
        assertEquals(1_000L, policy.reserve(immediate = false))
        policy.connected()
        assertFalse(policy.begin())
        assertEquals(1_000L, policy.reserve(immediate = false))
    }

    @Test
    fun closeSuppressesReservedAndFutureRetries() {
        val policy = AssociationServiceRebindPolicy()
        assertEquals(1_000L, policy.reserve(immediate = false))
        policy.close()
        assertFalse(policy.begin())
        assertEquals(AssociationServiceRebindPolicy.NO_RETRY, policy.reserve(immediate = true))
    }
}
