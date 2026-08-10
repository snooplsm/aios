package com.aios.messaging.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.aios.messaging.R
import com.aios.messaging.model.MessageUiState
import com.aios.messaging.ui.MainActivity

class MessageNotificationCoordinator(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun notifyIncoming(message: MessageUiState) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.message_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val open = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SENDTO
            data = Uri.fromParts("smsto", message.address, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val publicVersion = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("New message")
            .setContentText("Open AIOS Messages")
            .build()
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(message.address)
            .setContentText(message.body.take(160))
            .setStyle(Notification.BigTextStyle().bigText(message.body.take(1_000)))
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    message.threadId.hashCode(),
                    open,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        manager?.notify(message.threadId.hashCode(), notification)
    }

    private companion object {
        const val CHANNEL = "messages"
    }
}
