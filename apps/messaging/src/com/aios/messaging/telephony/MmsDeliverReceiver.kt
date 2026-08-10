package com.aios.messaging.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager
import com.aios.messaging.MessagingRuntime

/** Fail closed until carrier-tested PDU persistence and download are implemented. */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        MessagingRuntime.reportMmsBlocked()
        resultCode = SmsManager.RESULT_ERROR_GENERIC_FAILURE
    }
}
