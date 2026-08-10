package com.aios.messaging.telephony

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import com.aios.messaging.MessagingRuntime
import com.aios.messaging.mms.MmsTransport

/** Explicit carrier callback; durable work is completed off the receiver thread. */
class MmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action.orEmpty()
        if (action !in ACTIONS) return
        val token = intent.data?.lastPathSegment.orEmpty()
        if (token.isBlank()) {
            resultCode = SmsManager.MMS_ERROR_UNSPECIFIED
            return
        }
        val carrierResult = resultCode
        val pending = goAsync()
        MessagingRuntime.completeMmsOperation(
            action = action,
            token = token,
            resultCode = carrierResult,
            response = intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA),
            httpStatus = intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0),
        ) { handled ->
            pending.resultCode = if (handled) Activity.RESULT_OK else SmsManager.MMS_ERROR_UNSPECIFIED
            pending.finish()
        }
    }

    private companion object {
        val ACTIONS = setOf(
            MmsTransport.ACTION_SENT,
            MmsTransport.ACTION_DOWNLOADED,
            MmsTransport.ACTION_NOTIFY_RESPONSE,
        )
    }
}
