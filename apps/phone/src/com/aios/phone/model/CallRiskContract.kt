package com.aios.phone.model

enum class CallRiskLabel(val wireValue: String, val headline: String) {
    LIKELY_LEGITIMATE("likely_legitimate", "Likely legitimate"),
    UNKNOWN("unknown", "Still evaluating"),
    SUSPICIOUS("suspicious", "Suspicious call"),
    HIGH_RISK("high_risk", "High-risk call");

    fun accepts(score: Int): Boolean = when (this) {
        LIKELY_LEGITIMATE -> score in 0..15
        UNKNOWN -> score in 0..49
        SUSPICIOUS -> score in 50..74
        HIGH_RISK -> score in 75..100
    }

    companion object {
        fun fromWire(value: String?): CallRiskLabel? =
            entries.firstOrNull { it.wireValue == value }
    }
}

enum class CallRiskSource(val wireValue: String, val displayName: String) {
    HEURISTIC("heuristic", "On-device signals"),
    MODEL("model", "On-device model");

    companion object {
        fun fromWire(value: String?): CallRiskSource? =
            entries.firstOrNull { it.wireValue == value }
    }
}

object CallRiskSemantics {
    private const val MAX_REASON_CODE_CHARS = 70

    fun isValidReasonCode(value: String?): Boolean =
        value != null && value.length in 1..MAX_REASON_CODE_CHARS &&
            value.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }

    fun shouldReplace(currentRevision: Long?, candidateRevision: Long): Boolean =
        candidateRevision > 0L && (currentRevision == null || candidateRevision > currentRevision)

    fun explanation(label: CallRiskLabel, reasonCode: String): String {
        val normalized = reasonCode.removePrefix("model_")
        return when (normalized) {
            "known_contact" -> "This number matches one of your contacts."
            "business_intent", "service_request", "appointment_request",
            "estimate_request", "customer_request" ->
                "The caller is discussing a service or scheduling request."
            "insufficient_evidence" -> "Not enough caller speech yet to classify this call."
            "remote_access" -> "The caller asked for remote access to a device."
            "gift_card_payment" -> "The caller mentioned payment with gift cards."
            "robocall_instruction" -> "The call contains a common robocall instruction."
            "credential_request" -> "The caller requested a password or verification credential."
            "money_transfer" -> "The caller requested a money transfer."
            "coercive_threat" -> "The caller used a threat to pressure immediate action."
            "cryptocurrency_payment" -> "The caller requested cryptocurrency payment."
            "do_not_disconnect" -> "The caller pressured you to stay on the line."
            "authority_impersonation" -> "The caller may be impersonating an authority."
            "artificial_urgency" -> "The caller used unusually urgent language."
            else -> when (label) {
                CallRiskLabel.LIKELY_LEGITIMATE ->
                    "Current on-device signals look consistent with a legitimate call."
                CallRiskLabel.UNKNOWN ->
                    "The current evidence is not strong enough to classify this call."
                CallRiskLabel.SUSPICIOUS ->
                    "On-device signals found patterns commonly associated with spam."
                CallRiskLabel.HIGH_RISK ->
                    "Strong on-device signals match common scam patterns."
            }
        }
    }
}
