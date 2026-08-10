package com.aios.messaging.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionSelectionPolicyTest {
    @Test
    fun activeOwnerChoiceWinsOverSystemDefault() {
        assertEquals(
            2,
            SubscriptionSelectionPolicy.select(listOf(1, 2), 2, 1),
        )
    }

    @Test
    fun staleChoiceFallsBackToActiveDefault() {
        assertEquals(
            1,
            SubscriptionSelectionPolicy.select(listOf(1, 2), 9, 1),
        )
    }

    @Test
    fun oneActiveSubscriptionNeedsNoPrompt() {
        assertEquals(
            7,
            SubscriptionSelectionPolicy.select(listOf(7), null, null),
        )
    }

    @Test
    fun ambiguousOrEmptyInventoryFailsClosed() {
        assertNull(SubscriptionSelectionPolicy.select(listOf(1, 2), null, null))
        assertNull(SubscriptionSelectionPolicy.select(emptyList(), null, null))
    }
}
