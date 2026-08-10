package com.aios.messaging.data

import android.app.role.RoleManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.PhoneNumberUtils
import android.os.Handler
import android.os.Looper
import com.aios.messaging.model.ConversationUiState
import com.aios.messaging.model.MessagePolicy
import com.aios.messaging.model.MessageDeliveryState
import com.aios.messaging.model.MessageTransport
import com.aios.messaging.model.MessageUiState
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MessagingRepository(private val context: Context) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun loadConversations(callback: (Result<List<ConversationUiState>>) -> Unit) {
        background(callback) {
            requireSmsRole()
            val conversations = linkedMapOf<Long, ConversationUiState>()
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                SMS_PROJECTION,
                null,
                null,
                "${Telephony.Sms.DATE} DESC LIMIT $MAX_SCANNED_MESSAGES",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val message = message(cursor)
                    if (message.threadId <= 0L || message.address.isBlank()) continue
                    mergeConversation(conversations, message)
                }
            }
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                MMS_PROJECTION,
                null,
                null,
                "${Telephony.Mms.DATE} DESC LIMIT $MAX_SCANNED_MMS",
            )?.use { cursor ->
                while (cursor.moveToNext()) mergeConversation(conversations, mmsMessage(cursor))
            }
            conversations.values.sortedByDescending(ConversationUiState::lastAtEpochMillis)
        }
    }

    fun loadMessages(threadId: Long, callback: (Result<List<MessageUiState>>) -> Unit) {
        background(callback) {
            requireSmsRole()
            val messages = mutableListOf<MessageUiState>()
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                SMS_PROJECTION,
                "${Telephony.Sms.THREAD_ID}=?",
                arrayOf(threadId.toString()),
                "${Telephony.Sms.DATE} DESC LIMIT $MAX_THREAD_MESSAGES",
            )?.use { cursor -> while (cursor.moveToNext()) messages += message(cursor) }
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                MMS_PROJECTION,
                "${Telephony.Mms.THREAD_ID}=?",
                arrayOf(threadId.toString()),
                "${Telephony.Mms.DATE} DESC LIMIT $MAX_THREAD_MESSAGES",
            )?.use { cursor -> while (cursor.moveToNext()) messages += mmsMessage(cursor) }
            messages.sortedBy(MessageUiState::atEpochMillis).takeLast(MAX_THREAD_MESSAGES)
        }
    }

    fun markThreadRead(threadId: Long) {
        executor.execute {
            if (!isSmsRoleHeld()) return@execute
            val values = ContentValues().apply {
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
            }
            context.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms.THREAD_ID}=? AND ${Telephony.Sms.READ}=0",
                arrayOf(threadId.toString()),
            )
            context.contentResolver.update(
                Telephony.Mms.CONTENT_URI,
                values,
                "${Telephony.Mms.THREAD_ID}=? AND ${Telephony.Mms.READ}=0",
                arrayOf(threadId.toString()),
            )
        }
    }

    fun sendSms(
        address: String,
        body: String,
        callback: (Result<MessageUiState>) -> Unit,
    ) {
        background(callback) {
            requireSmsRole()
            val normalizedAddress = PhoneNumberUtils.normalizeNumber(address)
            val normalizedBody = MessagePolicy.normalizedBody(body)
            require(normalizedAddress.isNotBlank() && normalizedBody.isNotBlank()) {
                "A phone number and message are required"
            }
            val subscriptionId = SubscriptionManager.getDefaultSmsSubscriptionId()
            require(subscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                "Choose a default SMS SIM in system settings"
            }
            val manager = checkNotNull(context.getSystemService(SmsManager::class.java)) {
                "SMS service is unavailable"
            }.createForSubscriptionId(subscriptionId)
            val parts = manager.divideMessage(normalizedBody)
            if (parts.size == 1) {
                manager.sendTextMessage(normalizedAddress, null, normalizedBody, null, null)
            } else {
                manager.sendMultipartTextMessage(
                    normalizedAddress,
                    null,
                    ArrayList(parts),
                    null,
                    null,
                )
            }
            insertMessage(
                Telephony.Sms.Sent.CONTENT_URI,
                normalizedAddress,
                normalizedBody,
                System.currentTimeMillis(),
                subscriptionId,
                read = true,
                outgoing = true,
            )
        }
    }

    fun deleteMessage(
        id: Long,
        transport: MessageTransport,
        callback: (Result<Unit>) -> Unit,
    ) {
        background(callback) {
            requireSmsRole()
            val root = if (transport == MessageTransport.SMS) {
                Telephony.Sms.CONTENT_URI
            } else {
                Telephony.Mms.CONTENT_URI
            }
            val deleted = context.contentResolver.delete(
                ContentUris.withAppendedId(root, id), null, null)
            check(deleted == 1) { "Message is no longer available" }
        }
    }

    fun storeIncoming(
        address: String,
        body: String,
        timestamp: Long,
        subscriptionId: Int,
        callback: (Result<MessageUiState>) -> Unit,
    ) {
        background(callback) {
            requireSmsRole()
            insertMessage(
                Telephony.Sms.Inbox.CONTENT_URI,
                address,
                body,
                timestamp.coerceAtLeast(1L),
                subscriptionId,
                read = false,
                outgoing = false,
            )
        }
    }

    fun close() {
        executor.shutdown()
    }

    private fun insertMessage(
        destination: Uri,
        address: String,
        body: String,
        timestamp: Long,
        subscriptionId: Int,
        read: Boolean,
        outgoing: Boolean,
    ): MessageUiState {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, timestamp)
            put(Telephony.Sms.READ, if (read) 1 else 0)
            put(Telephony.Sms.SEEN, if (read) 1 else 0)
            put("sub_id", subscriptionId)
        }
        val inserted = checkNotNull(context.contentResolver.insert(destination, values)) {
            "SMS provider rejected the message"
        }
        val id = ContentUris.parseId(inserted)
        var threadId = 0L
        context.contentResolver.query(
            inserted,
            arrayOf(Telephony.Sms.THREAD_ID),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) threadId = cursor.getLong(0) }
        return MessageUiState(id, threadId, address, body, timestamp, outgoing, read)
    }

    private fun message(cursor: Cursor): MessageUiState {
        val type = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE))
        return MessageUiState(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms._ID)),
            threadId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)),
            address = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)).orEmpty(),
            body = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)).orEmpty(),
            atEpochMillis = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)),
            outgoing = type != Telephony.Sms.MESSAGE_TYPE_INBOX,
            read = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.READ)) != 0,
        )
    }

    private fun mmsMessage(cursor: Cursor): MessageUiState {
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms._ID))
        val box = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_BOX))
        val type = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Mms.MESSAGE_TYPE))
        val outgoing = box != Telephony.Mms.MESSAGE_BOX_INBOX
        val parts = mmsParts(id)
        val body = when {
            parts.text.isNotBlank() -> parts.text
            parts.hasPhoto -> "[Photo]"
            type == MMS_NOTIFICATION_IND -> "[MMS waiting to download]"
            else -> "[MMS]"
        }
        return MessageUiState(
            id = id,
            threadId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms.THREAD_ID)),
            address = mmsAddress(id, outgoing),
            body = body,
            atEpochMillis = cursor.getLong(
                cursor.getColumnIndexOrThrow(Telephony.Mms.DATE),
            ).toEpochMillis(),
            outgoing = outgoing,
            read = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Mms.READ)) != 0,
            transport = MessageTransport.MMS,
            hasPhoto = parts.hasPhoto,
            deliveryState = when {
                type == MMS_NOTIFICATION_IND -> MessageDeliveryState.WAITING_DOWNLOAD
                box == Telephony.Mms.MESSAGE_BOX_OUTBOX -> MessageDeliveryState.SENDING
                box == Telephony.Mms.MESSAGE_BOX_FAILED -> MessageDeliveryState.FAILED
                else -> MessageDeliveryState.COMPLETE
            },
        )
    }

    private fun mmsParts(messageId: Long): MmsParts {
        val text = StringBuilder()
        var hasPhoto = false
        context.contentResolver.query(
            Telephony.Mms.Part.CONTENT_URI,
            arrayOf(Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT),
            "${Telephony.Mms.Part.MSG_ID}=?",
            arrayOf(messageId.toString()),
            Telephony.Mms.Part._ID,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val contentType = cursor.getString(0).orEmpty()
                if (contentType == "text/plain") {
                    cursor.getString(1)?.takeIf(String::isNotBlank)?.let { value ->
                        if (text.isNotEmpty()) text.append('\n')
                        text.append(value)
                    }
                }
                if (contentType.startsWith("image/")) hasPhoto = true
            }
        }
        return MmsParts(text.toString(), hasPhoto)
    }

    private fun mmsAddress(messageId: Long, outgoing: Boolean): String {
        val preferred = if (outgoing) MMS_ADDRESS_TO else MMS_ADDRESS_FROM
        var fallback = ""
        context.contentResolver.query(
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

    private fun mergeConversation(
        conversations: MutableMap<Long, ConversationUiState>,
        message: MessageUiState,
    ) {
        if (message.threadId <= 0L || message.address.isBlank()) return
        val current = conversations[message.threadId]
        val unread = current?.unread == true || (!message.outgoing && !message.read)
        if (current == null || message.atEpochMillis > current.lastAtEpochMillis) {
            conversations[message.threadId] = ConversationUiState(
                threadId = message.threadId,
                address = message.address,
                displayName = contactName(message.address) ?: message.address,
                lastBody = message.body,
                lastAtEpochMillis = message.atEpochMillis,
                unread = unread,
            )
        } else if (unread != current.unread) {
            conversations[message.threadId] = current.copy(unread = unread)
        }
    }

    private fun contactName(address: String): String? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(address),
        )
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf(String::isNotBlank) else null
            }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun requireSmsRole() {
        check(isSmsRoleHeld()) { "Choose AIOS Messages as the SMS app first" }
    }

    private fun isSmsRoleHeld(): Boolean =
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true

    private fun <T> background(callback: (Result<T>) -> Unit, operation: () -> T) {
        executor.execute {
            val result = runCatching(operation)
            main.post { callback(result) }
        }
    }

    private companion object {
        const val MAX_SCANNED_MESSAGES = 500
        const val MAX_SCANNED_MMS = 200
        const val MAX_THREAD_MESSAGES = 200
        const val MMS_NOTIFICATION_IND = 0x82
        const val MMS_ADDRESS_FROM = 137
        const val MMS_ADDRESS_TO = 151
        val SMS_PROJECTION = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
        )
        val MMS_PROJECTION = arrayOf(
            Telephony.Mms._ID,
            Telephony.Mms.THREAD_ID,
            Telephony.Mms.DATE,
            Telephony.Mms.MESSAGE_BOX,
            Telephony.Mms.READ,
            Telephony.Mms.MESSAGE_TYPE,
        )
    }

    private data class MmsParts(val text: String, val hasPhoto: Boolean)

    private fun Long.toEpochMillis(): Long =
        coerceAtLeast(1L).coerceAtMost(Long.MAX_VALUE / 1_000L) * 1_000L
}
