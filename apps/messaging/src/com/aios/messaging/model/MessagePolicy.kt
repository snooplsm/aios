package com.aios.messaging.model

object MessagePolicy {
    const val MAX_BODY_CHARS = 4_096

    fun normalizedBody(value: String): String = value.trim().take(MAX_BODY_CHARS)

    /** Preserve delayed messages, but never let an invalid/network-future PDU reorder the inbox. */
    fun incomingTimestamp(claimedAtEpochMillis: Long, receivedAtEpochMillis: Long): Long {
        val received = receivedAtEpochMillis.coerceAtLeast(1L)
        return claimedAtEpochMillis.takeIf { it in 1L..received } ?: received
    }

    fun canSendSms(body: String, hasPhoto: Boolean): Boolean =
        normalizedBody(body).isNotEmpty() && !hasPhoto

    fun requiresMms(body: String, hasPhoto: Boolean): Boolean =
        hasPhoto && normalizedBody(body).length <= MAX_BODY_CHARS
}
