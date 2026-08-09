package com.aios.phone.data

import android.content.Context
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telecom.TelecomManager
import com.aios.phone.model.RecentCallUiState
import java.util.concurrent.Executors

/** Bounded, read-only CallLog projection. Raw rows are never logged or persisted. */
class CallHistoryRepository(
    private val context: Context,
    private val callback: (Result<List<RecentCallUiState>>) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { work ->
        Thread(work, "aios-phone-call-history")
    }

    fun reload() {
        worker.execute {
            val result = runCatching { query() }
            main.post { callback(result) }
        }
    }

    private fun query(): List<RecentCallUiState> {
        return try {
            query(CallLog.Calls.CONTENT_URI_WITH_VOICEMAIL)
        } catch (_: SecurityException) {
            // Some role implementations grant call-log access but not voicemail access.
            query(CallLog.Calls.CONTENT_URI)
        }
    }

    private fun query(uri: android.net.Uri): List<RecentCallUiState> {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                uri,
                PROJECTION,
                null,
                null,
                "${CallLog.Calls.DATE} DESC",
            ) ?: return emptyList()
            buildList {
                while (cursor.moveToNext() && size < MAX_ROWS) {
                    val id = cursor.getLong(0).toString()
                    val number = cursor.getString(1).orEmpty().take(MAX_NUMBER_CHARS)
                    val cachedName = cursor.getString(2).orEmpty().take(MAX_NAME_CHARS)
                    val type = cursor.getInt(3)
                    val date = cursor.getLong(4)
                    val duration = cursor.getLong(5).coerceAtLeast(0L)
                    val presentation = cursor.getInt(6)
                    val shownNumber = presentationLabel(presentation, number)
                    add(RecentCallUiState(
                        id = id,
                        displayName = cachedName.takeIf {
                            presentation == TelecomManager.PRESENTATION_ALLOWED && it.isNotBlank()
                        } ?: shownNumber,
                        number = if (presentation == TelecomManager.PRESENTATION_ALLOWED) {
                            number
                        } else {
                            ""
                        },
                        type = type,
                        timestampMillis = date,
                        durationSeconds = duration,
                    ))
                }
            }
        } finally {
            cursor?.close()
        }
    }

    private fun presentationLabel(presentation: Int, number: String): String =
        when (presentation) {
            TelecomManager.PRESENTATION_ALLOWED -> number.ifBlank { "Unknown caller" }
            TelecomManager.PRESENTATION_RESTRICTED -> "Private number"
            TelecomManager.PRESENTATION_PAYPHONE -> "Payphone"
            TelecomManager.PRESENTATION_UNAVAILABLE -> "Unavailable number"
            else -> "Unknown caller"
        }

    private companion object {
        val PROJECTION = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.NUMBER_PRESENTATION,
        )
        const val MAX_ROWS = 50
        const val MAX_NUMBER_CHARS = 80
        const val MAX_NAME_CHARS = 120
    }
}
