package com.aios.messaging.telephony

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager
import com.aios.messaging.MessagingRuntime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent).toList()
        if (messages.isEmpty()) {
            resultCode = SmsManager.RESULT_ERROR_GENERIC_FAILURE
            return
        }
        val groups = messages.groupBy { it.originatingAddress.orEmpty() }
            .filterKeys(String::isNotBlank)
        if (groups.isEmpty()) {
            resultCode = SmsManager.RESULT_ERROR_GENERIC_FAILURE
            return
        }
        val pending = goAsync()
        val remaining = AtomicInteger(groups.size)
        val succeeded = AtomicBoolean(true)
        val subscriptionId = intent.getIntExtra("subscription", -1)
        groups.forEach { (address, parts) ->
            MessagingRuntime.receiveSms(
                address = address,
                body = parts.joinToString(separator = "") { it.messageBody.orEmpty() },
                timestamp = parts.minOf { it.timestampMillis }.coerceAtLeast(1L),
                subscriptionId = subscriptionId,
            ) { stored ->
                if (!stored) succeeded.set(false)
                if (remaining.decrementAndGet() == 0) {
                    pending.resultCode = if (succeeded.get()) {
                        Activity.RESULT_OK
                    } else {
                        SmsManager.RESULT_ERROR_GENERIC_FAILURE
                    }
                    pending.finish()
                }
            }
        }
    }
}
