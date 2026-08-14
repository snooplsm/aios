package com.aios.phone.model

/** Shared UI/controller contract for the Call Intelligence policy surface. */
object AssistantPolicySemantics {
    const val MAX_EXCLUDED_CONVERSATIONS = 256
    const val MODE_OFF = "off"
    const val MODE_MISSED_ONLY = "missed_only"
    const val MODE_UNKNOWN_ONLY = "unknown_only"
    const val MODE_ALL = "all"

    val SELECTABLE_AUTO_ANSWER_MODES = listOf(
        MODE_MISSED_ONLY,
        MODE_UNKNOWN_ONLY,
        MODE_ALL,
    )
    val DIRECT_ANSWER_DELAY_MODES = listOf(
        "fixed_1000_ms",
        "fixed_2000_ms",
        "fixed_3000_ms",
        "fixed_4000_ms",
        "random_1010_3990_ms",
    )
    val MISSED_DELAY_OPTIONS_MILLIS = listOf(
        5_000L,
        10_000L,
        15_000L,
        20_000L,
        30_000L,
        45_000L,
        60_000L,
    )

    fun isKnownAnswerMode(mode: String): Boolean =
        mode == MODE_OFF || mode in SELECTABLE_AUTO_ANSWER_MODES

    fun isKnownDirectDelayMode(mode: String): Boolean = mode in DIRECT_ANSWER_DELAY_MODES

    fun safeAnswerMode(mode: String?): String =
        mode?.takeIf(::isKnownAnswerMode) ?: MODE_OFF

    fun safeDirectDelayMode(mode: String?): String =
        mode?.takeIf(::isKnownDirectDelayMode) ?: "fixed_2000_ms"

    fun safeUnavailableReason(reason: String?, automaticAnswerAvailable: Boolean): String =
        if (automaticAnswerAvailable) {
            ""
        } else {
            reason?.takeIf { it.matches(Regex("[a-z0-9_]{1,64}")) }
                ?: "service_unavailable"
        }

    fun safeDevelopmentUplinkTestActive(
        reportedActive: Boolean,
        automaticAnswerAvailable: Boolean,
    ): Boolean = reportedActive && !automaticAnswerAvailable

    fun modeAfterAutoAnswerToggle(current: String, enabled: Boolean): String = when {
        !enabled -> MODE_OFF
        current in SELECTABLE_AUTO_ANSWER_MODES -> current
        else -> MODE_UNKNOWN_ONLY
    }

    fun clampMissedDelay(millis: Long): Long = millis.coerceIn(3_000L, 60_000L)

    fun withConversationHistory(
        policy: AssistantPolicyUiState,
        addressHash: String,
        enabled: Boolean,
    ): AssistantPolicyUiState {
        if (!addressHash.matches(Regex("[0-9a-f]{64}"))) return policy
        val changed = policy.excludedCallerHistoryAddressHashes.toMutableSet()
        if (enabled) {
            changed.remove(addressHash)
        } else if (changed.size < MAX_EXCLUDED_CONVERSATIONS || addressHash in changed) {
            changed.add(addressHash)
        }
        return policy.copy(excludedCallerHistoryAddressHashes = changed)
    }
}
