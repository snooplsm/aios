package com.aios.phone.context

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val HEX = "0123456789abcdef"

/** Identifier-free call-log state that is safe to place in communication context. */
internal data class CallEventRecord(
    val sourceId: String,
    val address: String,
    val countryIso: String,
    val kind: CallEventKind,
    val eventAtEpochMillis: Long,
    val durationSeconds: Long,
    val isVideo: Boolean,
) {
    fun contextText(): String = buildString {
        append(kind.description)
        if (durationSeconds > 0L) append(" Duration: ").append(durationSeconds).append(" seconds.")
        if (isVideo) append(" Video call.")
    }

    /** HMAC avoids an unkeyed, cross-install address digest in the change ledger. */
    fun fingerprint(secret: ByteArray): String {
        require(secret.size >= 32) { "call-event fingerprint key is too short" }
        val value = listOf(
            address,
            countryIso,
            kind.name,
            eventAtEpochMillis.toString(),
            durationSeconds.toString(),
            isVideo.toString(),
        ).joinToString("\u0000")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return buildString(64) {
            for (item in mac.doFinal(value.toByteArray(Charsets.UTF_8))) {
                val unsigned = item.toInt() and 0xff
                append(HEX[unsigned ushr 4])
                append(HEX[unsigned and 0x0f])
            }
        }
    }

    override fun toString(): String =
        "CallEventRecord(sourceId=$sourceId,address=<redacted>,countryIso=$countryIso," +
            "kind=$kind,eventAtEpochMillis=$eventAtEpochMillis," +
            "durationSeconds=$durationSeconds,isVideo=$isVideo)"
}

internal enum class CallEventKind(val description: String) {
    INCOMING("Incoming call answered."),
    OUTGOING("Outgoing call."),
    MISSED("Missed incoming call."),
    VOICEMAIL("Incoming call sent to voicemail."),
    REJECTED("Incoming call declined."),
    BLOCKED("Blocked incoming call."),
    ANSWERED_EXTERNALLY("Incoming call answered on another device."),
    UNKNOWN("Call event."),
    ;

    companion object {
        fun fromCallLogType(value: Int): CallEventKind = when (value) {
            1 -> INCOMING
            2 -> OUTGOING
            3 -> MISSED
            4 -> VOICEMAIL
            5 -> REJECTED
            6 -> BLOCKED
            7 -> ANSWERED_EXTERNALLY
            else -> UNKNOWN
        }
    }
}

internal sealed interface CallEventMutation {
    data class Upsert(val record: CallEventRecord, val fingerprint: String) : CallEventMutation
    data class Delete(val sourceId: String) : CallEventMutation
}

/** Pure diff used by the provider client and host tests. */
internal object CallEventReconciler {
    const val MAX_INDEXED_EVENTS = 256

    fun reconcile(
        records: List<CallEventRecord>,
        indexed: Map<String, String>,
        secret: ByteArray,
    ): List<CallEventMutation> {
        val current = linkedMapOf<String, Pair<CallEventRecord, String>>()
        records.asSequence()
            .filter { it.sourceId.matches(SOURCE_ID) && it.eventAtEpochMillis > 0L }
            .sortedByDescending { it.eventAtEpochMillis }
            .distinctBy { it.sourceId }
            .take(MAX_INDEXED_EVENTS)
            .forEach { record -> current[record.sourceId] = record to record.fingerprint(secret) }

        return buildList {
            // Deletions run first so the service never exceeds the bounded live set.
            (indexed.keys - current.keys).sorted().forEach { add(CallEventMutation.Delete(it)) }
            current.values.forEach { (record, fingerprint) ->
                if (indexed[record.sourceId] != fingerprint) {
                    add(CallEventMutation.Upsert(record, fingerprint))
                }
            }
        }
    }

    private val SOURCE_ID = Regex("calllog:[1-9][0-9]{0,18}")
}
