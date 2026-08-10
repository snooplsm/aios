package com.aios.messaging.model

object MessagePolicy {
    const val MAX_BODY_CHARS = 4_096

    fun normalizedBody(value: String): String = value.trim().take(MAX_BODY_CHARS)

    fun canSendSms(body: String, hasPhoto: Boolean): Boolean =
        normalizedBody(body).isNotEmpty() && !hasPhoto

    fun requiresMms(body: String, hasPhoto: Boolean): Boolean =
        hasPhoto && normalizedBody(body).length <= MAX_BODY_CHARS
}
