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
}
