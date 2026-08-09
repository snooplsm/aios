package com.aios.phone.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.BaseColumns
import android.provider.VoicemailContract
import android.telecom.PhoneAccountHandle
import android.telephony.TelephonyManager
import com.aios.phone.model.VoicemailUiState
import java.util.UUID
import java.util.concurrent.Executors

/** Bounded voicemail projection. Provider row IDs and source packages never enter UI state. */
class VoicemailRepository(
    private val context: Context,
    private val callback: (Result<List<VoicemailUiState>>) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "aios-phone-voicemail")
    }
    private val entries = mutableMapOf<String, Entry>()
    private val idsByUri = mutableMapOf<Uri, String>()

    fun reload() {
        worker.execute {
            val result = runCatching { query() }
            main.post { callback(result) }
        }
    }

    @Synchronized
    fun contentUri(voicemailId: String): Uri? = entries[voicemailId]?.uri

    fun markRead(voicemailId: String) {
        val entry = synchronized(this) { entries[voicemailId] } ?: return
        worker.execute {
            runCatching {
                val values = android.content.ContentValues().apply {
                    put(VoicemailContract.Voicemails.IS_READ, 1)
                    put(VoicemailContract.Voicemails.NEW, 0)
                }
                context.contentResolver.update(entry.uri, values, null, null)
            }
            reload()
        }
    }

    fun requestContent(voicemailId: String): Boolean {
        val entry = synchronized(this) { entries[voicemailId] } ?: return false
        val sourcePackage = entry.sourcePackage.takeIf(String::isNotBlank) ?: return false
        context.sendBroadcast(
            Intent(VoicemailContract.ACTION_FETCH_VOICEMAIL, entry.uri)
                .setPackage(sourcePackage),
        )
        return true
    }

    private fun query(): List<VoicemailUiState> {
        val freshEntries = mutableMapOf<String, Entry>()
        val result = context.contentResolver.query(
            VoicemailContract.Voicemails.CONTENT_URI,
            PROJECTION,
            null,
            null,
            "${VoicemailContract.Voicemails.DATE} DESC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext() && size < MAX_ROWS) {
                    if (!isVisibleVoicemail(cursor)) continue
                    val providerId = cursor.getLong(0)
                    val sourcePackage = cursor.getString(9).orEmpty().take(MAX_PACKAGE_CHARS)
                    val uri = Uri.withAppendedPath(
                        VoicemailContract.Voicemails.CONTENT_URI,
                        providerId.toString(),
                    )
                    val id = synchronized(this@VoicemailRepository) {
                        idsByUri.getOrPut(uri) { "voicemail-${UUID.randomUUID()}" }
                    }
                    freshEntries[id] = Entry(uri, sourcePackage)
                    add(VoicemailUiState(
                        id = id,
                        number = cursor.getString(1).orEmpty().take(MAX_NUMBER_CHARS)
                            .ifBlank { "Unknown caller" },
                        timestampMillis = cursor.getLong(2),
                        durationSeconds = cursor.getLong(3).coerceAtLeast(0L),
                        isRead = cursor.getInt(4) != 0,
                        hasContent = cursor.getInt(5) != 0,
                        mimeType = cursor.getString(6).orEmpty().take(MAX_MIME_CHARS),
                        transcription = cursor.getString(7).orEmpty()
                            .take(MAX_TRANSCRIPTION_CHARS),
                    ))
                }
            }
        } ?: emptyList()
        synchronized(this) {
            entries.clear()
            entries.putAll(freshEntries)
            idsByUri.keys.retainAll(freshEntries.values.mapTo(mutableSetOf()) { it.uri })
        }
        return result
    }

    private fun isVisibleVoicemail(cursor: Cursor): Boolean {
        if (cursor.getInt(8) == 0) return true
        val sourcePackage = cursor.getString(9).orEmpty()
        val component = ComponentName.unflattenFromString(cursor.getString(10).orEmpty())
            ?: return false
        val accountId = cursor.getString(11) ?: return false
        val handle = PhoneAccountHandle(component, accountId)
        if (context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED) return false
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return false
        val visualVoicemailPackage = runCatching {
            telephony.createForPhoneAccountHandle(handle)?.visualVoicemailPackageName
        }.getOrNull()
        return visualVoicemailPackage != null && sourcePackage == visualVoicemailPackage
    }

    private data class Entry(val uri: Uri, val sourcePackage: String)

    private companion object {
        val PROJECTION = arrayOf(
            BaseColumns._ID,
            VoicemailContract.Voicemails.NUMBER,
            VoicemailContract.Voicemails.DATE,
            VoicemailContract.Voicemails.DURATION,
            VoicemailContract.Voicemails.IS_READ,
            VoicemailContract.Voicemails.HAS_CONTENT,
            VoicemailContract.Voicemails.MIME_TYPE,
            VoicemailContract.Voicemails.TRANSCRIPTION,
            VoicemailContract.Voicemails.IS_OMTP_VOICEMAIL,
            VoicemailContract.Voicemails.SOURCE_PACKAGE,
            VoicemailContract.Voicemails.PHONE_ACCOUNT_COMPONENT_NAME,
            VoicemailContract.Voicemails.PHONE_ACCOUNT_ID,
        )
        const val MAX_ROWS = 50
        const val MAX_NUMBER_CHARS = 80
        const val MAX_MIME_CHARS = 80
        const val MAX_PACKAGE_CHARS = 200
        const val MAX_TRANSCRIPTION_CHARS = 2_000
    }
}
