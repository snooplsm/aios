package com.aios.messaging.telephony

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.aios.messaging.MessagingRuntime

class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = intent?.data?.schemeSpecificPart.orEmpty()
        val body = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (intent?.action != ACTION_RESPOND_VIA_MESSAGE ||
            address.isBlank() || body.isBlank()) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        MessagingRuntime.sendQuickReply(address, body) { stopSelfResult(startId) }
        return START_NOT_STICKY
    }

    private companion object {
        const val ACTION_RESPOND_VIA_MESSAGE = "android.intent.action.RESPOND_VIA_MESSAGE"
    }
}
