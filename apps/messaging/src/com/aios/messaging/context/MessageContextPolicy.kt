package com.aios.messaging.context

import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal data class ProviderContextRecord(
    val sourceType: String,
    val sourceId: String,
    val address: String,
    val countryIso: String,
    val eventAtEpochMillis: Long,
    val text: String,
)

/** Pure validation and keyed change detection for Telephony-provider reconciliation. */
internal object MessageContextPolicy {
    const val SOURCE_SMS = "sms"
    const val SOURCE_MMS = "mms"
    const val MAX_ADDRESS_CHARS = 80
    const val MAX_INDEX_CHARS = 4_096

    private val sourceId = Regex("[1-9][0-9]{0,18}")
    private val countryIso = Regex("[A-Z]{2}")

    fun sanitize(value: ProviderContextRecord): ProviderContextRecord? {
        if (value.sourceType != SOURCE_SMS && value.sourceType != SOURCE_MMS) return null
        if (!sourceId.matches(value.sourceId) || value.eventAtEpochMillis <= 0L) return null
        val address = value.address.trim().take(MAX_ADDRESS_CHARS)
        val text = value.text.trim().take(MAX_INDEX_CHARS)
        if (address.isBlank() || text.isBlank()) return null
        val country = value.countryIso.trim().uppercase()
            .takeIf(countryIso::matches)
            .orEmpty()
        return value.copy(address = address, countryIso = country, text = text)
    }

    fun fingerprint(value: ProviderContextRecord, secret: ByteArray): String {
        require(secret.size >= 16) { "message-context fingerprint secret is too short" }
        val record = requireNotNull(sanitize(value)) { "invalid provider context record" }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        listOf(
            record.sourceType,
            record.sourceId,
            record.address,
            record.countryIso,
            record.eventAtEpochMillis.toString(),
            record.text,
        ).forEach { field ->
            mac.update(field.toByteArray(StandardCharsets.UTF_8))
            mac.update(0)
        }
        return mac.doFinal().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
