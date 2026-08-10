package com.aios.phone.telecom

/** Keeps owner attention on a newly ringing call without stealing it for background calls. */
internal object CallSelectionPolicy {
    fun afterCallAdded(
        currentSelection: String?,
        newCallId: String,
        newCallIsRinging: Boolean,
        currentSelectionStillPresent: Boolean,
    ): String = when {
        newCallIsRinging -> newCallId
        currentSelection != null && currentSelectionStillPresent -> currentSelection
        else -> newCallId
    }

    fun afterStateChanged(
        currentSelection: String?,
        changedCallId: String?,
        changedCallIsRinging: Boolean,
    ): String? = if (changedCallIsRinging && changedCallId != null) {
        changedCallId
    } else {
        currentSelection
    }

    fun forOwnerPrompt(currentSelection: String?, promptCallId: String?): String? =
        promptCallId ?: currentSelection
}
