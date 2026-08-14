package com.aios.phone.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPolicySemanticsTest {
    @Test
    fun exposesEveryServiceSupportedAnswerMode() {
        assertEquals(
            listOf("missed_only", "unknown_only", "all"),
            AssistantPolicySemantics.SELECTABLE_AUTO_ANSWER_MODES,
        )
        assertTrue(AssistantPolicySemantics.isKnownAnswerMode("off"))
        AssistantPolicySemantics.SELECTABLE_AUTO_ANSWER_MODES.forEach {
            assertTrue(AssistantPolicySemantics.isKnownAnswerMode(it))
        }
        assertFalse(AssistantPolicySemantics.isKnownAnswerMode("unsupported"))
    }

    @Test
    fun directAnswerDelaysRemainExactlyOwnerRequestedSet() {
        assertEquals(
            listOf(
                "fixed_1000_ms",
                "fixed_2000_ms",
                "fixed_3000_ms",
                "fixed_4000_ms",
                "random_1010_3990_ms",
            ),
            AssistantPolicySemantics.DIRECT_ANSWER_DELAY_MODES,
        )
    }

    @Test
    fun malformedOrMissingBinderPolicyStringsFailClosed() {
        assertEquals("off", AssistantPolicySemantics.safeAnswerMode(null))
        assertEquals("off", AssistantPolicySemantics.safeAnswerMode("future_mode"))
        assertEquals("all", AssistantPolicySemantics.safeAnswerMode("all"))
        assertEquals(
            "fixed_2000_ms",
            AssistantPolicySemantics.safeDirectDelayMode(null),
        )
        assertEquals(
            "fixed_2000_ms",
            AssistantPolicySemantics.safeDirectDelayMode("fast"),
        )
        assertEquals(
            "service_unavailable",
            AssistantPolicySemantics.safeUnavailableReason(null, false),
        )
        assertEquals(
            "service_unavailable",
            AssistantPolicySemantics.safeUnavailableReason("bad reason!", false),
        )
        assertEquals(
            "caller_uplink_unvalidated",
            AssistantPolicySemantics.safeUnavailableReason(
                "caller_uplink_unvalidated",
                false,
            ),
        )
        assertEquals("", AssistantPolicySemantics.safeUnavailableReason(null, true))
        assertTrue(AssistantPolicySemantics.safeDevelopmentUplinkTestActive(true, false))
        assertFalse(AssistantPolicySemantics.safeDevelopmentUplinkTestActive(true, true))
        assertFalse(AssistantPolicySemantics.safeDevelopmentUplinkTestActive(false, false))
    }

    @Test
    fun autoAnswerTogglePreservesSelectedScope() {
        assertEquals(
            "missed_only",
            AssistantPolicySemantics.modeAfterAutoAnswerToggle("missed_only", true),
        )
        assertEquals(
            "unknown_only",
            AssistantPolicySemantics.modeAfterAutoAnswerToggle("off", true),
        )
        assertEquals(
            "off",
            AssistantPolicySemantics.modeAfterAutoAnswerToggle("all", false),
        )
    }

    @Test
    fun ringFirstChoicesCoverDefaultAndServiceBounds() {
        assertTrue(15_000L in AssistantPolicySemantics.MISSED_DELAY_OPTIONS_MILLIS)
        assertEquals(3_000L, AssistantPolicySemantics.clampMissedDelay(1L))
        assertEquals(60_000L, AssistantPolicySemantics.clampMissedDelay(Long.MAX_VALUE))
        assertEquals(20_000L, AssistantPolicySemantics.clampMissedDelay(20_000L))
    }

    @Test
    fun callerHistoryScopeCannotRemainEnabledWithoutSources() {
        val empty = AssistantPolicyUiState(
            callerHistoryEnabled = true,
            messageHistoryEnabled = false,
            callHistoryEnabled = false,
            photoHistoryEnabled = false,
        )

        assertFalse(empty.hasEnabledCallerHistorySource)
        assertFalse(empty.withoutEmptyCallerHistory().callerHistoryEnabled)
        val restored = empty.withCallerHistoryEnabled(true)
        assertTrue(restored.callerHistoryEnabled)
        assertTrue(restored.messageHistoryEnabled)
        assertTrue(restored.callHistoryEnabled)
        assertTrue(restored.photoHistoryEnabled)
    }

    @Test
    fun conversationExclusionsUseOnlyBoundedOpaqueHashes() {
        val hash = "a".repeat(64)
        val original = AssistantPolicyUiState(callerHistoryEnabled = true)
        val excluded = AssistantPolicySemantics.withConversationHistory(
            original,
            hash,
            enabled = false,
        )

        assertEquals(setOf(hash), excluded.excludedCallerHistoryAddressHashes)
        assertEquals(
            emptySet<String>(),
            AssistantPolicySemantics.withConversationHistory(
                excluded,
                hash,
                enabled = true,
            ).excludedCallerHistoryAddressHashes,
        )
        assertEquals(
            original,
            AssistantPolicySemantics.withConversationHistory(
                original,
                "+15555550182",
                enabled = false,
            ),
        )
    }
}
