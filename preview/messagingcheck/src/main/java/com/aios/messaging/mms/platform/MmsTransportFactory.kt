package com.aios.messaging.mms.platform

import android.content.Context
import com.aios.messaging.mms.MmsEvent
import com.aios.messaging.mms.MmsTransport

/** Public-SDK compile fixture; the production factory links AOSP's platform MMS sources. */
object MmsTransportFactory {
    fun create(context: Context, listener: MmsTransport.Listener): MmsTransport =
        object : MmsTransport {
            override val admitted = false
            override fun sendPhoto(
                address: String,
                body: String,
                photoUri: String,
                callback: (Result<MmsEvent>) -> Unit,
            ) = callback(Result.failure(IllegalStateException("platform MMS unavailable")))

            override fun receiveWapPush(
                pdu: ByteArray,
                subscriptionId: Int,
                callback: (Boolean) -> Unit,
            ) = callback(false)

            override fun complete(
                action: String,
                token: String,
                resultCode: Int,
                response: ByteArray?,
                httpStatus: Int,
                callback: (Boolean) -> Unit,
            ) = callback(false)

            override fun close() = Unit
        }
}
