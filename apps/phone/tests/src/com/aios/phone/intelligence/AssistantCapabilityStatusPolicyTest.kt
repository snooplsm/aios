package com.aios.phone.intelligence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantCapabilityStatusPolicyTest {
    @Test
    fun everyCallerInteractionCapabilityRefreshesThePolicy() {
        listOf(
            "streaming_asr_ready",
            "streaming_asr_unavailable",
            "speech_synthesis_ready",
            "speech_synthesis_unavailable",
            "receptionist_ready",
            "receptionist_unavailable",
        ).forEach {
            assertTrue(AssistantCapabilityStatusPolicy.shouldReload("availability", it))
        }
    }

    @Test
    fun perCallAndUnknownStatusesDoNotCausePolicyReloads() {
        assertFalse(AssistantCapabilityStatusPolicy.shouldReload("call-1", "asr_complete"))
        assertFalse(AssistantCapabilityStatusPolicy.shouldReload("availability", "future"))
        assertFalse(AssistantCapabilityStatusPolicy.shouldReload(null, null))
    }
}
