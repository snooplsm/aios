package com.aios.messaging.mms.platform

import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import com.aios.messaging.mms.MmsEvent
import com.aios.messaging.mms.MmsOperationKind
import com.aios.messaging.mms.MmsOperationPolicy
import com.aios.messaging.mms.MmsOperationState
import com.aios.messaging.mms.MmsPduProvider
import com.aios.messaging.mms.MmsTransport
import com.aios.messaging.telephony.MmsResultReceiver
import com.google.android.mms.ContentType
import com.google.android.mms.pdu.CharacterSets
import com.google.android.mms.pdu.EncodedStringValue
import com.google.android.mms.pdu.GenericPdu
import com.google.android.mms.pdu.NotificationInd
import com.google.android.mms.pdu.NotifyRespInd
import com.google.android.mms.pdu.PduBody
import com.google.android.mms.pdu.PduComposer
import com.google.android.mms.pdu.PduHeaders
import com.google.android.mms.pdu.PduParser
import com.google.android.mms.pdu.PduPart
import com.google.android.mms.pdu.PduPersister
import com.google.android.mms.pdu.RetrieveConf
import com.google.android.mms.pdu.SendConf
import com.google.android.mms.pdu.SendReq
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class PlatformMmsTransport(
    private val context: Context,
    private val listener: MmsTransport.Listener,
) : MmsTransport {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val store = MmsOperationStore(context)
    private val persister = PduPersister.getPduPersister(context)

    override val admitted: Boolean = Build.TYPE != "user"

    init {
        executor.execute(::recoverInterruptedOperations)
    }

    override fun sendPhoto(
        address: String,
        body: String,
        photoUri: String,
        callback: (Result<MmsEvent>) -> Unit,
    ) {
        executor.execute {
            val result = runCatching { sendPhoto(address, body, Uri.parse(photoUri)) }
            context.mainExecutor.execute { callback(result) }
            result.exceptionOrNull()?.let { notifyFailure("Photo message could not be submitted") }
        }
    }

    override fun receiveWapPush(
        pdu: ByteArray,
        subscriptionId: Int,
        callback: (Boolean) -> Unit,
    ) {
        executor.execute {
            val succeeded = runCatching { receiveNotification(pdu, subscriptionId) }
                .onFailure { notifyFailure("Incoming MMS could not be stored") }
                .getOrDefault(false)
            context.mainExecutor.execute { callback(succeeded) }
        }
    }

    override fun complete(
        action: String,
        token: String,
        resultCode: Int,
        response: ByteArray?,
        httpStatus: Int,
        callback: (Boolean) -> Unit,
    ) {
        executor.execute {
            val handled = runCatching {
                val record = store.get(token) ?: return@runCatching false
                if (!actionMatches(record.kind, action)) return@runCatching false
                if (record.state == MmsOperationState.SUCCEEDED ||
                    record.state == MmsOperationState.FAILED) return@runCatching true
                check(record.state == MmsOperationState.SUBMITTED) {
                    "MMS callback arrived before submission was durable"
                }
                when (record.kind) {
                    MmsOperationKind.SEND -> completeSend(record, resultCode, response, httpStatus)
                    MmsOperationKind.DOWNLOAD -> completeDownload(record, resultCode, httpStatus)
                    MmsOperationKind.NOTIFY_RESPONSE -> completeNotifyResponse(record, resultCode)
                }
                true
            }.onFailure {
                store.get(token)?.let { record ->
                    store.transition(record.token, MmsOperationState.FAILED, resultCode)
                    if (record.kind == MmsOperationKind.SEND) markSendFailed(record.providerUri)
                }
                notifyFailure("MMS carrier operation failed")
            }.getOrDefault(false)
            val latest = store.get(token)
            if (latest == null || latest.state == MmsOperationState.SUCCEEDED ||
                latest.state == MmsOperationState.FAILED) {
                MmsPduProvider.remove(context, token)
            }
            context.mainExecutor.execute { callback(handled) }
        }
    }

    override fun close() {
        executor.shutdown()
        store.close()
    }

    private fun sendPhoto(address: String, body: String, photoUri: Uri): MmsEvent {
        check(admitted) { "MMS is enabled only on debuggable AIOS builds until carrier gates pass" }
        requireSmsRole()
        val normalized = PhoneNumberUtils.normalizeNumber(address)
        require(normalized.isNotBlank()) { "A valid phone number is required" }
        val subscriptionId = effectiveSubscription(SubscriptionManager.getDefaultSmsSubscriptionId())
        val manager = smsManager(subscriptionId)
        val limits = carrierLimits(manager)
        val normalizedBody = body.trim().take(4_096)
        val bodyBytes = normalizedBody.toByteArray(StandardCharsets.UTF_8).size
        val photoBudget = limits.maxMessageSize - bodyBytes - PDU_OVERHEAD_BYTES
        val photo = MmsPhotoTranscoder.encode(
            context,
            photoUri,
            limits.maxImageWidth,
            limits.maxImageHeight,
            photoBudget,
        )
        val request = sendRequest(normalized, normalizedBody, photo)
        val token = UUID.randomUUID().toString()
        val pduUri = MmsPduProvider.create(context, token)
        val now = System.currentTimeMillis()
        store.create(newRecord(token, MmsOperationKind.SEND, subscriptionId, pduUri, now))
        var providerUri: Uri? = null
        try {
            providerUri = persister.persist(
                request,
                Telephony.Mms.Outbox.CONTENT_URI,
                true,
                false,
                null,
            ).canonicalMmsUri()
            store.updateUris(token, providerUri.toString(), pduUri.toString())
            updateProviderEnvelope(providerUri, subscriptionId, now / 1_000L, read = true)
            val stored = persister.load(providerUri) as? SendReq
                ?: error("MMS provider did not return the persisted send request")
            val encoded = PduComposer(context, stored).make()
                ?: error("MMS PDU could not be composed")
            require(encoded.size <= limits.maxMessageSize) {
                "Photo exceeds this carrier's MMS size limit"
            }
            FileOutputStream(MmsPduProvider.fileFor(context, token), false).use { it.write(encoded) }
            check(store.transition(token, MmsOperationState.PROVIDER_PERSISTED))
            check(store.transition(token, MmsOperationState.SUBMITTED))
            manager.sendMultimediaMessage(
                context,
                pduUri,
                null,
                null,
                resultIntent(MmsTransport.ACTION_SENT, token),
                ContentUris.parseId(providerUri),
            )
            return readEvent(providerUri, outgoing = true)
        } catch (failure: Throwable) {
            val failedFrom = store.get(token)?.state ?: MmsOperationState.PREPARING
            store.transition(token, MmsOperationState.FAILED, LOCAL_FAILURE)
            providerUri?.let {
                markSendFailed(it.toString())
                if (!MmsOperationPolicy.keepProviderRowAfterFailure(failedFrom)) {
                    runCatching { context.contentResolver.delete(it, null, null) }
                }
            }
            MmsPduProvider.remove(context, token)
            throw failure
        }
    }

    private fun receiveNotification(pushData: ByteArray, requestedSubscriptionId: Int): Boolean {
        requireSmsRole()
        require(pushData.isNotEmpty() && pushData.size <= MAX_WAP_PUSH_BYTES) {
            "Invalid MMS WAP push"
        }
        val subscriptionId = effectiveSubscription(requestedSubscriptionId)
        val manager = smsManager(subscriptionId)
        val limits = carrierLimits(manager)
        val parsed = PduParser(pushData, limits.supportContentDisposition).parse()
            ?: error("Invalid MMS WAP push")
        if (parsed !is NotificationInd) return true
        val transaction = parsed.transactionId ?: error("MMS notification lacks transaction ID")
        if (limits.appendTransactionId) appendTransactionId(parsed, transaction)
        val transactionString = PduPersister.toIsoString(transaction)
        val duplicate = duplicateNotification(transactionString)
        if (duplicate != null) {
            if (duplicate.messageType == PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND &&
                !store.hasActive(duplicate.uri.toString(), MmsOperationKind.DOWNLOAD)) {
                submitDownload(
                    duplicate.uri,
                    subscriptionId,
                    duplicate.transactionId,
                    duplicate.contentLocation,
                    duplicate.receivedAtSeconds,
                    duplicate.expirySeconds,
                )
            }
            return true
        }
        val nowSeconds = System.currentTimeMillis() / 1_000L
        val inbox = persister.persist(
            parsed,
            Telephony.Mms.Inbox.CONTENT_URI,
            true,
            false,
            null,
        ).canonicalMmsUri()
        updateProviderEnvelope(inbox, subscriptionId, nowSeconds, read = false)
        submitDownload(
            inbox,
            subscriptionId,
            transactionString,
            PduPersister.toIsoString(parsed.contentLocation),
            nowSeconds,
            parsed.expiry,
        )
        return true
    }

    private fun submitDownload(
        notificationUri: Uri,
        subscriptionId: Int,
        transactionId: String,
        contentLocation: String,
        receivedAtSeconds: Long,
        expirySeconds: Long,
    ) {
        require(contentLocation.isNotBlank()) { "MMS notification lacks content location" }
        val token = UUID.randomUUID().toString()
        val pduUri = MmsPduProvider.create(context, token)
        val now = System.currentTimeMillis()
        store.create(
            newRecord(token, MmsOperationKind.DOWNLOAD, subscriptionId, pduUri, now).copy(
                providerUri = notificationUri.toString(),
                transactionId = transactionId,
                contentLocation = contentLocation,
                receivedAtSeconds = receivedAtSeconds,
                expirySeconds = expirySeconds,
            ),
        )
        try {
            check(store.transition(token, MmsOperationState.PROVIDER_PERSISTED))
            check(store.transition(token, MmsOperationState.SUBMITTED))
            smsManager(subscriptionId).downloadMultimediaMessage(
                context,
                contentLocation,
                pduUri,
                null,
                resultIntent(MmsTransport.ACTION_DOWNLOADED, token),
                ContentUris.parseId(notificationUri),
            )
        } catch (failure: Throwable) {
            store.transition(token, MmsOperationState.FAILED, LOCAL_FAILURE)
            MmsPduProvider.remove(context, token)
            throw failure
        }
    }

    private fun completeSend(
        record: MmsOperationRecord,
        resultCode: Int,
        response: ByteArray?,
        httpStatus: Int,
    ) {
        if (resultCode != MmsOperationPolicy.RESULT_OK) {
            markSendFailed(record.providerUri)
            check(store.transition(record.token, MmsOperationState.FAILED, carrierCode(
                resultCode,
                httpStatus,
            )))
            notifyFailure("Photo message was rejected by the carrier")
            return
        }
        val limits = carrierLimits(smsManager(record.subscriptionId))
        val confirmation = response?.let {
            PduParser(it, limits.supportContentDisposition).parse() as? SendConf
        } ?: error("Carrier returned no valid MMS send confirmation")
        val providerUri = Uri.parse(record.providerUri)
        val values = ContentValues().apply {
            put(Telephony.Mms.RESPONSE_STATUS, confirmation.responseStatus)
            confirmation.messageId?.let { put(Telephony.Mms.MESSAGE_ID, PduPersister.toIsoString(it)) }
            put(Telephony.Mms.DATE_SENT, System.currentTimeMillis() / 1_000L)
        }
        check(context.contentResolver.update(providerUri, values, null, null) == 1) {
            "MMS provider status update failed"
        }
        if (confirmation.responseStatus != PduHeaders.RESPONSE_STATUS_OK) {
            markSendFailed(record.providerUri)
            check(store.transition(
                record.token,
                MmsOperationState.FAILED,
                confirmation.responseStatus,
            ))
            notifyFailure("Carrier did not accept the photo message")
            return
        }
        val sent = persister.move(providerUri, Telephony.Mms.Sent.CONTENT_URI).canonicalMmsUri()
        check(store.transition(record.token, MmsOperationState.SUCCEEDED, resultCode))
        notifyCompleted(readEvent(sent, outgoing = true))
    }

    private fun completeDownload(
        record: MmsOperationRecord,
        resultCode: Int,
        httpStatus: Int,
    ) {
        if (resultCode != MmsOperationPolicy.RESULT_OK) {
            check(store.transition(record.token, MmsOperationState.FAILED, carrierCode(
                resultCode,
                httpStatus,
            )))
            notifyFailure("Incoming MMS download failed")
            return
        }
        val limits = carrierLimits(smsManager(record.subscriptionId))
        val file = MmsPduProvider.fileFor(context, record.token)
        require(file.length() in 1..(limits.maxMessageSize + DOWNLOAD_SLOP_BYTES).toLong()) {
            "Downloaded MMS exceeds its carrier bound"
        }
        val retrieved = PduParser(file.readBytes(), limits.supportContentDisposition).parse()
            as? RetrieveConf ?: error("Downloaded MMS is not a retrieve confirmation")
        require(retrieved.retrieveStatus == PduHeaders.RETRIEVE_STATUS_OK) {
            "Carrier reported MMS retrieve failure ${retrieved.retrieveStatus}"
        }
        if (retrieved.transactionId == null && record.transactionId.isNotBlank()) {
            retrieved.transactionId = PduPersister.getBytes(record.transactionId)
        }
        val inbox = persister.persist(
            retrieved,
            Telephony.Mms.Inbox.CONTENT_URI,
            true,
            false,
            null,
        ).canonicalMmsUri()
        updateProviderEnvelope(inbox, record.subscriptionId, record.receivedAtSeconds, read = false)
        val event = readEvent(inbox, outgoing = false)
        check(context.contentResolver.delete(Uri.parse(record.providerUri), null, null) == 1) {
            "MMS notification placeholder could not be retired"
        }
        check(store.transition(record.token, MmsOperationState.SUCCEEDED, resultCode))
        notifyCompleted(event)
        runCatching { submitNotifyResponse(record, inbox, limits.notifyWapMmsc) }
    }

    private fun submitNotifyResponse(
        download: MmsOperationRecord,
        receivedUri: Uri,
        notifyWapMmsc: Boolean,
    ) {
        val token = UUID.randomUUID().toString()
        val pduUri = MmsPduProvider.create(context, token)
        val now = System.currentTimeMillis()
        try {
            val response = NotifyRespInd(
                PduHeaders.CURRENT_MMS_VERSION,
                PduPersister.getBytes(download.transactionId),
                PduHeaders.STATUS_RETRIEVED,
            )
            val encoded = PduComposer(context, response).make()
                ?: error("MMS retrieve acknowledgement could not be composed")
            FileOutputStream(MmsPduProvider.fileFor(context, token), false).use { it.write(encoded) }
            store.create(
                newRecord(
                    token,
                    MmsOperationKind.NOTIFY_RESPONSE,
                    download.subscriptionId,
                    pduUri,
                    now,
                ).copy(
                    providerUri = receivedUri.toString(),
                    contentLocation = download.contentLocation,
                    transactionId = download.transactionId,
                ),
            )
            check(store.transition(token, MmsOperationState.PROVIDER_PERSISTED))
            check(store.transition(token, MmsOperationState.SUBMITTED))
            smsManager(download.subscriptionId).sendMultimediaMessage(
                context,
                pduUri,
                download.contentLocation.takeIf { notifyWapMmsc },
                null,
                resultIntent(MmsTransport.ACTION_NOTIFY_RESPONSE, token),
                ContentUris.parseId(receivedUri),
            )
        } catch (failure: Throwable) {
            store.get(token)?.let {
                store.transition(token, MmsOperationState.FAILED, LOCAL_FAILURE)
            }
            MmsPduProvider.remove(context, token)
            throw failure
        }
    }

    private fun completeNotifyResponse(record: MmsOperationRecord, resultCode: Int) {
        val target = if (resultCode == MmsOperationPolicy.RESULT_OK) {
            MmsOperationState.SUCCEEDED
        } else {
            MmsOperationState.FAILED
        }
        check(store.transition(record.token, target, resultCode))
    }

    private fun sendRequest(address: String, text: String, photo: EncodedPhoto): SendReq {
        val request = SendReq()
        request.to = arrayOf(EncodedStringValue(address))
        request.date = System.currentTimeMillis() / 1_000L
        request.messageClass = PduHeaders.MESSAGE_CLASS_PERSONAL_STR.toByteArray()
        request.expiry = DEFAULT_EXPIRY_SECONDS
        request.priority = PduHeaders.PRIORITY_NORMAL
        request.deliveryReport = PduHeaders.VALUE_NO
        request.readReport = PduHeaders.VALUE_NO
        val body = PduBody()
        val imageName = "image000001.jpg"
        val image = PduPart().apply {
            contentType = ContentType.IMAGE_JPEG.toByteArray()
            contentLocation = imageName.toByteArray()
            contentId = "image000001".toByteArray()
            data = photo.bytes
        }
        body.addPart(image)
        val textName = "text000002.txt"
        if (text.isNotBlank()) {
            body.addPart(PduPart().apply {
                charset = CharacterSets.UTF_8
                contentType = ContentType.TEXT_PLAIN.toByteArray()
                contentLocation = textName.toByteArray()
                contentId = "text000002".toByteArray()
                data = text.toByteArray(StandardCharsets.UTF_8)
            })
        }
        val smilBody = if (text.isBlank()) {
            "<par dur=\"5000ms\"><img src=\"$imageName\"/></par>"
        } else {
            "<par dur=\"5000ms\"><img src=\"$imageName\"/><text src=\"$textName\"/></par>"
        }
        body.addPart(0, PduPart().apply {
            contentId = "smil".toByteArray()
            contentLocation = "smil.xml".toByteArray()
            contentType = ContentType.APP_SMIL.toByteArray()
            data = ("<smil><head><layout><root-layout/></layout></head><body>" +
                smilBody + "</body></smil>").toByteArray(StandardCharsets.UTF_8)
        })
        request.body = body
        request.messageSize = body.partsSize().toLong()
        return request
    }

    private fun PduBody.partsSize(): Int {
        var result = 0L
        for (index in 0 until partsNum) result += getPart(index).dataLength.toLong()
        require(result <= Int.MAX_VALUE) { "MMS body is too large" }
        return result.toInt()
    }

    private fun duplicateNotification(transactionId: String): DuplicateNotification? {
        val now = System.currentTimeMillis() / 1_000L
        return context.contentResolver.query(
            Telephony.Mms.CONTENT_URI,
            arrayOf(
                Telephony.Mms._ID,
                Telephony.Mms.MESSAGE_TYPE,
                Telephony.Mms.TRANSACTION_ID,
                Telephony.Mms.CONTENT_LOCATION,
                Telephony.Mms.DATE,
                Telephony.Mms.EXPIRY,
            ),
            "((${Telephony.Mms.MESSAGE_TYPE}=?) OR (${Telephony.Mms.MESSAGE_TYPE}=?)) AND " +
                "(${Telephony.Mms.EXPIRY}=0 OR ${Telephony.Mms.EXPIRY}>?) AND " +
                "${Telephony.Mms.TRANSACTION_ID}=?",
            arrayOf(
                PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND.toString(),
                PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF.toString(),
                now.toString(),
                transactionId,
            ),
            "${Telephony.Mms._ID} DESC",
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null else DuplicateNotification(
                ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, cursor.getLong(0)),
                cursor.getInt(1),
                cursor.getString(2).orEmpty(),
                cursor.getString(3).orEmpty(),
                cursor.getLong(4).coerceAtLeast(1L),
                cursor.getLong(5).coerceAtLeast(1L),
            )
        }
    }

    private fun appendTransactionId(notification: NotificationInd, transactionId: ByteArray) {
        val location = notification.contentLocation ?: return
        if (location.isNotEmpty() && location.last() == '='.code.toByte()) {
            notification.contentLocation = location + transactionId
        }
    }

    private fun updateProviderEnvelope(uri: Uri, subId: Int, dateSeconds: Long, read: Boolean) {
        val values = ContentValues().apply {
            put(Telephony.Mms.SUBSCRIPTION_ID, subId)
            put(Telephony.Mms.DATE, dateSeconds.coerceAtLeast(1L))
            put(Telephony.Mms.READ, if (read) 1 else 0)
            put(Telephony.Mms.SEEN, if (read) 1 else 0)
        }
        check(context.contentResolver.update(uri, values, null, null) == 1) {
            "MMS provider envelope update failed"
        }
    }

    private fun readEvent(uri: Uri, outgoing: Boolean): MmsEvent {
        val id = ContentUris.parseId(uri)
        var threadId = 0L
        var dateSeconds = 0L
        context.contentResolver.query(
            uri,
            arrayOf(Telephony.Mms.THREAD_ID, Telephony.Mms.DATE),
            null,
            null,
            null,
        )?.use { cursor ->
            check(cursor.moveToFirst()) { "MMS provider row disappeared" }
            threadId = cursor.getLong(0)
            dateSeconds = cursor.getLong(1)
        } ?: error("MMS provider is unavailable")
        val text = StringBuilder()
        var hasPhoto = false
        context.contentResolver.query(
            Telephony.Mms.Part.CONTENT_URI,
            arrayOf(Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT),
            "${Telephony.Mms.Part.MSG_ID}=?",
            arrayOf(id.toString()),
            Telephony.Mms.Part._ID,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val type = cursor.getString(0).orEmpty()
                if (type == ContentType.TEXT_PLAIN) {
                    cursor.getString(1)?.takeIf(String::isNotBlank)?.let {
                        if (text.isNotEmpty()) text.append('\n')
                        text.append(it)
                    }
                }
                if (type.startsWith("image/")) hasPhoto = true
            }
        }
        return MmsEvent(
            providerId = id,
            threadId = threadId,
            address = mmsAddress(id, outgoing),
            text = text.toString().ifBlank { if (hasPhoto) "[Photo]" else "[MMS]" },
            atEpochMillis = dateSeconds.toEpochMillis(),
            outgoing = outgoing,
            hasPhoto = hasPhoto,
        )
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
                if (value.isBlank() || value == PduHeaders.FROM_INSERT_ADDRESS_TOKEN_STR) continue
                if (fallback.isBlank()) fallback = value
                if (cursor.getInt(1) == preferred) return value
            }
        }
        return fallback
    }

    private fun markSendFailed(rawUri: String) {
        if (rawUri.isBlank()) return
        runCatching {
            context.contentResolver.update(
                Uri.parse(rawUri),
                ContentValues().apply { put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_FAILED) },
                null,
                null,
            )
        }
    }

    private fun recoverInterruptedOperations() {
        store.recover(System.currentTimeMillis()).forEach { record ->
            MmsPduProvider.remove(context, record.token)
            if (record.kind == MmsOperationKind.SEND) markSendFailed(record.providerUri)
        }
        store.deleteTerminalOlderThan(
            System.currentTimeMillis() - MmsOperationPolicy.MAX_PENDING_AGE_MILLIS,
        )
        MmsPduProvider.removeOrphans(context, store.activeTokens())
    }

    private fun effectiveSubscription(requested: Int): Int {
        val candidate = requested.takeIf { SubscriptionManager.isValidSubscriptionId(it) }
            ?: SubscriptionManager.getDefaultSmsSubscriptionId()
        require(SubscriptionManager.isValidSubscriptionId(candidate)) {
            "Choose an SMS SIM in system settings"
        }
        return candidate
    }

    private fun smsManager(subscriptionId: Int): SmsManager =
        checkNotNull(context.getSystemService(SmsManager::class.java)) {
            "MMS service is unavailable"
        }.createForSubscriptionId(subscriptionId)

    private fun carrierLimits(manager: SmsManager): CarrierLimits {
        val values = manager.carrierConfigValues
        return CarrierLimits(
            maxMessageSize = values.getInt(
                SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE,
                DEFAULT_MAX_MESSAGE_BYTES,
            ).coerceIn(MIN_MAX_MESSAGE_BYTES, ABSOLUTE_MAX_MESSAGE_BYTES),
            maxImageWidth = values.getInt(
                SmsManager.MMS_CONFIG_MAX_IMAGE_WIDTH,
                DEFAULT_IMAGE_DIMENSION,
            ),
            maxImageHeight = values.getInt(
                SmsManager.MMS_CONFIG_MAX_IMAGE_HEIGHT,
                DEFAULT_IMAGE_DIMENSION,
            ),
            supportContentDisposition = values.getBoolean(
                SmsManager.MMS_CONFIG_SUPPORT_MMS_CONTENT_DISPOSITION,
                true,
            ),
            appendTransactionId = values.getBoolean(
                SmsManager.MMS_CONFIG_APPEND_TRANSACTION_ID,
                false,
            ),
            notifyWapMmsc = values.getBoolean(
                SmsManager.MMS_CONFIG_NOTIFY_WAP_MMSC_ENABLED,
                false,
            ),
        )
    }

    private fun resultIntent(action: String, token: String): PendingIntent {
        val intent = Intent(context, MmsResultReceiver::class.java).apply {
            this.action = action
            data = Uri.Builder().scheme("aios-mms").authority("operation")
                .appendPath(token).build()
        }
        return PendingIntent.getBroadcast(
            context,
            token.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun requireSmsRole() {
        check(context.getSystemService(RoleManager::class.java)
            ?.isRoleHeld(RoleManager.ROLE_SMS) == true) {
            "Choose AIOS Messages as the SMS app first"
        }
    }

    private fun notifyCompleted(event: MmsEvent) =
        context.mainExecutor.execute { listener.onCompleted(event) }

    private fun notifyFailure(message: String) =
        context.mainExecutor.execute { listener.onFailed(message) }

    private fun actionMatches(kind: MmsOperationKind, action: String): Boolean = when (kind) {
        MmsOperationKind.SEND -> action == MmsTransport.ACTION_SENT
        MmsOperationKind.DOWNLOAD -> action == MmsTransport.ACTION_DOWNLOADED
        MmsOperationKind.NOTIFY_RESPONSE -> action == MmsTransport.ACTION_NOTIFY_RESPONSE
    }

    private fun newRecord(
        token: String,
        kind: MmsOperationKind,
        subscriptionId: Int,
        pduUri: Uri,
        nowMillis: Long,
    ) = MmsOperationRecord(
        token = token,
        kind = kind,
        state = MmsOperationState.PREPARING,
        providerUri = "",
        pduUri = pduUri.toString(),
        subscriptionId = subscriptionId,
        transactionId = "",
        contentLocation = "",
        receivedAtSeconds = 0L,
        expirySeconds = 0L,
        carrierResult = 0,
        createdAtMillis = nowMillis,
        updatedAtMillis = nowMillis,
    )

    private fun Uri.canonicalMmsUri(): Uri =
        ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, ContentUris.parseId(this))

    private fun Long.toEpochMillis(): Long =
        coerceAtLeast(1L).coerceAtMost(Long.MAX_VALUE / 1_000L) * 1_000L

    private fun carrierCode(resultCode: Int, httpStatus: Int): Int =
        if (httpStatus == 0) resultCode else -httpStatus

    private data class CarrierLimits(
        val maxMessageSize: Int,
        val maxImageWidth: Int,
        val maxImageHeight: Int,
        val supportContentDisposition: Boolean,
        val appendTransactionId: Boolean,
        val notifyWapMmsc: Boolean,
    )

    private data class DuplicateNotification(
        val uri: Uri,
        val messageType: Int,
        val transactionId: String,
        val contentLocation: String,
        val receivedAtSeconds: Long,
        val expirySeconds: Long,
    )

    private companion object {
        const val DEFAULT_MAX_MESSAGE_BYTES = 300 * 1_024
        const val MIN_MAX_MESSAGE_BYTES = 64 * 1_024
        const val ABSOLUTE_MAX_MESSAGE_BYTES = 10 * 1_024 * 1_024
        const val DOWNLOAD_SLOP_BYTES = 128 * 1_024
        const val PDU_OVERHEAD_BYTES = 24 * 1_024
        const val DEFAULT_IMAGE_DIMENSION = 1_280
        const val MAX_WAP_PUSH_BYTES = 256 * 1_024
        const val DEFAULT_EXPIRY_SECONDS = 7L * 24L * 60L * 60L
        const val MMS_ADDRESS_FROM = 137
        const val MMS_ADDRESS_TO = 151
        const val LOCAL_FAILURE = -10_000
    }
}
