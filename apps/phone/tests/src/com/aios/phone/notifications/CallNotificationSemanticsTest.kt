package com.aios.phone.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallNotificationSemanticsTest {
    @Test
    fun ringingPresentationNeverIncludesTranscript() {
        val result = CallNotificationSemantics.present(
            ringing = true,
            aiHandling = true,
            riskHeadline = "High-risk call",
            latestIncomingTranscript = "The private caller text",
        )

        assertEquals("Incoming call", result.contentText)
        assertNull(result.subText)
        assertFalse(result.containsPrivateTranscript)
    }

    @Test
    fun liveCallerTextIsBoundedNormalizedAndMarkedPrivate() {
        val result = CallNotificationSemantics.present(
            ringing = false,
            aiHandling = true,
            riskHeadline = "Likely legitimate",
            latestIncomingTranscript = "  Need\nservice\u202e   at " + "x".repeat(300),
        )

        assertTrue(result.contentText.startsWith("Caller: Need service at "))
        assertEquals(CallNotificationSemantics.MAX_CONTENT_CHARS, result.contentText.length)
        assertEquals("AI receptionist \u00b7 Likely legitimate", result.subText)
        assertTrue(result.containsPrivateTranscript)
        assertFalse(result.contentText.contains('\u202e'))
    }

    @Test
    fun emptyTranscriptFallsBackToRiskStatus() {
        val result = CallNotificationSemantics.present(
            ringing = false,
            aiHandling = false,
            riskHeadline = "Suspicious call",
            latestIncomingTranscript = " \n\t ",
        )

        assertEquals("Ongoing call \u00b7 Suspicious call", result.contentText)
        assertNull(result.subText)
        assertFalse(result.containsPrivateTranscript)
    }
}
