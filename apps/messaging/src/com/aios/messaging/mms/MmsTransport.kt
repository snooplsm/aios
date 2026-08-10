package com.aios.messaging.mms

data class MmsEvent(
    val providerId: Long,
    val threadId: Long,
    val address: String,
    val text: String,
    val atEpochMillis: Long,
    val outgoing: Boolean,
    val hasPhoto: Boolean,
    val subscriptionId: Int,
    val associationToken: String = "",
)

interface MmsTransport {
    val admitted: Boolean

    fun sendPhoto(
        address: String,
        body: String,
        photoUri: String,
        subscriptionId: Int,
        associationToken: String,
        callback: (Result<MmsEvent>) -> Unit,
    )

    fun receiveWapPush(
        pdu: ByteArray,
        subscriptionId: Int,
        callback: (Boolean) -> Unit,
    )

    fun complete(
        action: String,
        token: String,
        resultCode: Int,
        response: ByteArray?,
        httpStatus: Int,
        callback: (Boolean) -> Unit,
    )

    /** Retires a replayable carrier success after media association is durably admitted. */
    fun acknowledgeMediaAssociation(associationToken: String)

    fun close()

    interface Listener {
        fun onCompleted(event: MmsEvent)
        fun onFailed(message: String, associationToken: String?)
    }

    companion object {
        const val ACTION_SENT = "com.aios.messaging.MMS_SENT"
        const val ACTION_DOWNLOADED = "com.aios.messaging.MMS_DOWNLOADED"
        const val ACTION_NOTIFY_RESPONSE = "com.aios.messaging.MMS_NOTIFY_RESPONSE"
    }
}
