package com.aios.messaging.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager
import com.aios.messaging.MessagingRuntime

class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val data = intent.getByteArrayExtra("data")
        if (intent.type != MMS_MIME || data == null || data.isEmpty()) {
            resultCode = SmsManager.RESULT_ERROR_GENERIC_FAILURE
            return
        }
        val pending = goAsync()
        val subscriptionId = intent.getIntExtra("subscription", -1)
        MessagingRuntime.receiveMms(data, subscriptionId) { stored ->
            pending.resultCode = if (stored) {
                android.app.Activity.RESULT_OK
            } else {
                SmsManager.RESULT_ERROR_GENERIC_FAILURE
            }
            pending.finish()
        }
    }

    private companion object {
        const val MMS_MIME = "application/vnd.wap.mms-message"
    }
}
