package com.aios.phone.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantServiceRebindPolicyTest {
    @Test
    fun terminalBindingCanReserveImmediateRecovery() {
        val policy = AssistantServiceRebindPolicy()

        assertEquals(0L, policy.reserve(immediate = true))
        assertTrue(policy.begin())
    }

    @Test
    fun failedBindsBackOffAndCapAtOneMinute() {
        val policy = AssistantServiceRebindPolicy()

        listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L)
            .forEach { expected ->
                assertEquals(expected, policy.reserve(immediate = false))
                assertTrue(policy.begin())
            }
    }

    @Test
    fun onlyOneRetryCanBeScheduled() {
        val policy = AssistantServiceRebindPolicy()

        assertEquals(1_000L, policy.reserve(immediate = false))
        assertEquals(
            AssistantServiceRebindPolicy.NO_RETRY,
            policy.reserve(immediate = true),
        )
        assertTrue(policy.begin())
        assertFalse(policy.begin())
    }

    @Test
    fun successfulConnectionResetsBackoff() {
        val policy = AssistantServiceRebindPolicy()
        assertEquals(1_000L, policy.reserve(immediate = false))
        assertTrue(policy.begin())
        assertEquals(2_000L, policy.reserve(immediate = false))
        assertTrue(policy.begin())

        policy.connected()

        assertEquals(1_000L, policy.reserve(immediate = false))
    }

    @Test
    fun closePermanentlySuppressesRetry() {
        val policy = AssistantServiceRebindPolicy()
        assertEquals(1_000L, policy.reserve(immediate = false))

        policy.close()

        assertFalse(policy.begin())
        assertEquals(
            AssistantServiceRebindPolicy.NO_RETRY,
            policy.reserve(immediate = true),
        )
    }
}
