package com.aios.messaging.context

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import android.telephony.TelephonyManager
import java.util.Locale

/** Bounded, restartable projection of authoritative SMS/MMS provider rows. */
internal class MessageContextProvider(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    fun highWatermark(sourceType: String): Long {
        val (uri, idColumn) = provider(sourceType)
        return resolver.query(
            uri,
            arrayOf(idColumn),
            null,
            null,
            "$idColumn DESC LIMIT 1",
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
            ?: error("Telephony provider returned no cursor")
    }

    fun page(sourceType: String, afterId: Long, throughId: Long, limit: Int): Page {
        require(afterId >= 0L && throughId >= afterId && limit in 1..MAX_PAGE) {
            "invalid message-context provider page"
        }
        if (afterId == throughId) return Page(emptyList(), afterId, true)
        return when (sourceType) {
            MessageContextPolicy.SOURCE_SMS -> smsPage(afterId, throughId, limit)
            MessageContextPolicy.SOURCE_MMS -> mmsPage(afterId, throughId, limit)
            else -> throw IllegalArgumentException("unknown message-context source")
        }
    }

    fun exact(sourceType: String, sourceId: String): ProviderContextRecord? {
        val id = sourceId.toLongOrNull()?.takeIf { it > 0L } ?: return null
        return page(sourceType, id - 1L, id, 1).records
            .singleOrNull { it.sourceId == sourceId }
    }

    private fun smsPage(afterId: Long, throughId: Long, limit: Int): Page {
        val records = mutableListOf<ProviderContextRecord>()
        var scanned = 0
        var nextId = afterId
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            SMS_PROJECTION,
            "${Telephony.Sms._ID}>? AND ${Telephony.Sms._ID}<=?",
            arrayOf(afterId.toString(), throughId.toString()),
            "${Telephony.Sms._ID} ASC LIMIT $limit",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                scanned++
                val id = cursor.getLong(0)
                nextId = id
                val type = cursor.getInt(4)
                if (type != Telephony.Sms.MESSAGE_TYPE_INBOX &&
                    type != Telephony.Sms.MESSAGE_TYPE_SENT) continue
                record(
                    MessageContextPolicy.SOURCE_SMS,
                    id,
                    cursor.getString(1).orEmpty(),
                    cursor.getLong(3),
                    cursor.getString(2).orEmpty(),
                )?.let(records::add)
            }
        } ?: error("SMS provider returned no cursor")
        return Page(records, nextId, scanned < limit || nextId >= throughId)
    }

    private fun mmsPage(afterId: Long, throughId: Long, limit: Int): Page {
        val records = mutableListOf<ProviderContextRecord>()
        var scanned = 0
        var nextId = afterId
        resolver.query(
            Telephony.Mms.CONTENT_URI,
            MMS_PROJECTION,
            "${Telephony.Mms._ID}>? AND ${Telephony.Mms._ID}<=?",
            arrayOf(afterId.toString(), throughId.toString()),
            "${Telephony.Mms._ID} ASC LIMIT $limit",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                scanned++
                val id = cursor.getLong(0)
                nextId = id
                val box = cursor.getInt(2)
                val messageType = cursor.getInt(3)
                if ((box != Telephony.Mms.MESSAGE_BOX_INBOX &&
                        box != Telephony.Mms.MESSAGE_BOX_SENT) ||
                    messageType == MMS_NOTIFICATION_IND) continue
                val outgoing = box == Telephony.Mms.MESSAGE_BOX_SENT
                val parts = mmsParts(id)
                val text = buildString {
                    append(parts.text)
                    if (parts.hasPhoto && !parts.text.contains("[Photo]")) {
                        if (isNotEmpty()) append('\n')
                        append("[Photo]")
                    }
                    if (isEmpty()) append("[MMS]")
                }
                record(
                    MessageContextPolicy.SOURCE_MMS,
                    id,
                    mmsAddress(id, outgoing),
                    cursor.getLong(1).toEpochMillis(),
                    text,
                )?.let(records::add)
            }
        } ?: error("MMS provider returned no cursor")
        return Page(records, nextId, scanned < limit || nextId >= throughId)
    }

    private fun record(
        sourceType: String,
        id: Long,
        address: String,
        eventAtEpochMillis: Long,
        text: String,
    ): ProviderContextRecord? = MessageContextPolicy.sanitize(
        ProviderContextRecord(
            sourceType = sourceType,
            sourceId = id.toString(),
            address = address,
            countryIso = countryIso(),
            eventAtEpochMillis = eventAtEpochMillis,
            text = text,
        ),
    )

    private fun mmsParts(messageId: Long): Parts {
        val text = StringBuilder()
        var hasPhoto = false
        resolver.query(
            Telephony.Mms.Part.CONTENT_URI,
            arrayOf(Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT),
            "${Telephony.Mms.Part.MSG_ID}=?",
            arrayOf(messageId.toString()),
            Telephony.Mms.Part._ID,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val contentType = cursor.getString(0).orEmpty()
                if (contentType == "text/plain" &&
                    text.length < MessageContextPolicy.MAX_INDEX_CHARS) {
                    cursor.getString(1)?.trim()?.takeIf(String::isNotBlank)?.let { value ->
                        if (text.isNotEmpty()) text.append('\n')
                        text.append(value.take(
                            MessageContextPolicy.MAX_INDEX_CHARS - text.length,
                        ))
                    }
                }
                if (contentType.startsWith("image/")) hasPhoto = true
            }
        }
        return Parts(text.toString(), hasPhoto)
    }

    private fun mmsAddress(messageId: Long, outgoing: Boolean): String {
        val preferred = if (outgoing) MMS_ADDRESS_TO else MMS_ADDRESS_FROM
        var fallback = ""
        resolver.query(
            Telephony.Mms.Addr.getAddrUriForMessage(messageId.toString()),
            arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val value = cursor.getString(0).orEmpty().substringBefore("/TYPE=")
                if (value.isBlank() || value == "insert-address-token") continue
                if (fallback.isBlank()) fallback = value
                if (cursor.getInt(1) == preferred) return value
            }
        }
        return fallback
    }

    private fun countryIso(): String {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        return runCatching {
            telephony?.simCountryIso?.takeIf(String::isNotBlank)
                ?: telephony?.networkCountryIso?.takeIf(String::isNotBlank)
        }.getOrNull() ?: Locale.getDefault().country
    }

    private fun provider(sourceType: String): Pair<Uri, String> = when (sourceType) {
        MessageContextPolicy.SOURCE_SMS -> Telephony.Sms.CONTENT_URI to Telephony.Sms._ID
        MessageContextPolicy.SOURCE_MMS -> Telephony.Mms.CONTENT_URI to Telephony.Mms._ID
        else -> throw IllegalArgumentException("unknown message-context source")
    }

    data class Page(
        val records: List<ProviderContextRecord>,
        val nextId: Long,
        val complete: Boolean,
    )

    private data class Parts(val text: String, val hasPhoto: Boolean)

    private companion object {
        const val MAX_PAGE = 256
        const val MMS_NOTIFICATION_IND = 0x82
        const val MMS_ADDRESS_FROM = 137
        const val MMS_ADDRESS_TO = 151
        val SMS_PROJECTION = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
        )
        val MMS_PROJECTION = arrayOf(
            Telephony.Mms._ID,
            Telephony.Mms.DATE,
            Telephony.Mms.MESSAGE_BOX,
            Telephony.Mms.MESSAGE_TYPE,
        )
    }

    private fun Long.toEpochMillis(): Long =
        coerceAtLeast(1L).coerceAtMost(Long.MAX_VALUE / 1_000L) * 1_000L
}
