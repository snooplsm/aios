package com.aios.phone.model

/** Immutable, host-testable projection of the call-assistant policy. */
data class AssistantPolicyUiState(
    val available: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val processingEnabled: Boolean = false,
    val callerHistoryEnabled: Boolean = false,
    val messageHistoryEnabled: Boolean = true,
    val callHistoryEnabled: Boolean = true,
    val photoHistoryEnabled: Boolean = true,
    val excludedCallerHistoryAddressHashes: Set<String> = emptySet(),
    val answerMode: String = "off",
    val answerDelayMode: String = "fixed_2000_ms",
    val missedDelayMillis: Long = 15_000L,
    val automaticAnswerAvailable: Boolean = false,
    val automaticAnswerUnavailableReason: String = "service_unavailable",
    val error: String? = null,
) {
    val autoAnswerEnabled: Boolean get() = answerMode != "off"
    val hasEnabledCallerHistorySource: Boolean
        get() = messageHistoryEnabled || callHistoryEnabled || photoHistoryEnabled

    fun withCallerHistoryEnabled(enabled: Boolean): AssistantPolicyUiState =
        if (enabled && !hasEnabledCallerHistorySource) {
            copy(
                callerHistoryEnabled = true,
                messageHistoryEnabled = true,
                callHistoryEnabled = true,
                photoHistoryEnabled = true,
            )
        } else {
            copy(callerHistoryEnabled = enabled)
        }

    fun withoutEmptyCallerHistory(): AssistantPolicyUiState =
        if (callerHistoryEnabled && !hasEnabledCallerHistorySource) {
            copy(callerHistoryEnabled = false)
        } else {
            this
        }
}
