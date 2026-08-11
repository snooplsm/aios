package com.aios.messaging.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagePolicyTest {
    @Test
    fun smsRequiresTextAndNoPhoto() {
        assertTrue(MessagePolicy.canSendSms(" hello ", hasPhoto = false))
        assertFalse(MessagePolicy.canSendSms("", hasPhoto = false))
        assertFalse(MessagePolicy.canSendSms("hello", hasPhoto = true))
    }

    @Test
    fun photoDraftRequiresMmsAndBodiesAreBounded() {
        assertTrue(MessagePolicy.requiresMms("", hasPhoto = true))
        assertEquals(
            MessagePolicy.MAX_BODY_CHARS,
            MessagePolicy.normalizedBody("x".repeat(MessagePolicy.MAX_BODY_CHARS + 1)).length,
        )
    }

    @Test
    fun incomingTimestampPreservesDelayedMessages() {
        assertEquals(900L, MessagePolicy.incomingTimestamp(900L, 1_000L))
        assertEquals(1_000L, MessagePolicy.incomingTimestamp(1_000L, 1_000L))
    }

    @Test
    fun incomingTimestampReplacesInvalidOrFutureNetworkTime() {
        assertEquals(1_000L, MessagePolicy.incomingTimestamp(0L, 1_000L))
        assertEquals(1_000L, MessagePolicy.incomingTimestamp(-1L, 1_000L))
        assertEquals(1_000L, MessagePolicy.incomingTimestamp(1_001L, 1_000L))
        assertEquals(1L, MessagePolicy.incomingTimestamp(5L, 0L))
    }
}
