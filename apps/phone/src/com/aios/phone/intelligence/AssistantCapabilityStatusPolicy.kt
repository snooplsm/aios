package com.aios.phone.intelligence

/** Identifies service-wide capability changes that require a fresh policy projection. */
object AssistantCapabilityStatusPolicy {
    private val CAPABILITY_DETAILS = setOf(
        "streaming_asr_ready",
        "streaming_asr_unavailable",
        "speech_synthesis_ready",
        "speech_synthesis_unavailable",
        "receptionist_ready",
        "receptionist_unavailable",
    )

    fun shouldReload(callId: String?, detail: String?): Boolean =
        callId == "availability" && detail in CAPABILITY_DETAILS
}
