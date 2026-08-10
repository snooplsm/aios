package com.aios.messaging.context

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aios.messaging.MessagingRuntime

/** Re-arms authoritative provider reconciliation after boot and SMS-role changes. */
class MessageContextLifecycleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            DEFAULT_SMS_PACKAGE_CHANGED,
            -> {
                MessagingRuntime.refreshRole()
                MessageContextReconcileJobService.schedule(context.applicationContext)
            }
        }
    }

    private companion object {
        const val DEFAULT_SMS_PACKAGE_CHANGED =
            "android.provider.action.DEFAULT_SMS_PACKAGE_CHANGED"
    }
}
