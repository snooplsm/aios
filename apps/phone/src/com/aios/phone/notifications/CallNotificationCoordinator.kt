package com.aios.phone.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import com.aios.phone.R
import com.aios.phone.model.AssistantCallUiState
import com.aios.phone.model.CallUiState
import com.aios.phone.model.RiskUiState
import com.aios.phone.ui.InCallActivity

class CallNotificationCoordinator(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val shown = mutableSetOf<String>()
    private val silenced = mutableSetOf<String>()

    init {
        val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        manager?.createNotificationChannels(listOf(
            NotificationChannel(
                INCOMING_CHANNEL,
                context.getString(R.string.call_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(ringtone, attributes)
                enableVibration(true)
            },
            NotificationChannel(
                SILENT_INCOMING_CHANNEL,
                "Silenced phone calls",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
            },
            NotificationChannel(
                ONGOING_CHANNEL,
                "Ongoing phone calls",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setSound(null, null)
                enableVibration(false)
            },
        ))
    }

    fun showIncomingOrOngoing(callId: String, calls: List<CallUiState>) {
        calls.firstOrNull { it.id == callId }?.let { show(it, null, null) }
    }

    fun sync(
        calls: List<CallUiState>,
        assistantCalls: Map<String, AssistantCallUiState>,
        risks: Map<String, RiskUiState>,
    ) {
        val live = calls.mapTo(mutableSetOf()) { it.id }
        shown.filterNot(live::contains).toList().forEach(::cancel)
        calls.forEach { call -> show(call, assistantCalls[call.id], risks[call.id]) }
    }

    fun cancel(callId: String) {
        manager?.cancel(notificationId(callId))
        shown.remove(callId)
        silenced.remove(callId)
    }

    fun silence(calls: List<CallUiState>) {
        calls.filter { it.isRinging }.forEach { call ->
            silenced.add(call.id)
            show(call, null, null)
        }
    }

    private fun show(
        call: CallUiState,
        assistantState: AssistantCallUiState?,
        risk: RiskUiState?,
    ) {
        val person = Person.Builder().setName(call.displayName).setImportant(true).build()
        val content = PendingIntent.getActivity(
            context,
            notificationId(call.id),
            Intent(context, InCallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val style = if (call.isRinging) {
            Notification.CallStyle.forIncomingCall(
                person,
                actionIntent(call.id, CallActionReceiver.ACTION_DECLINE, 1),
                actionIntent(call.id, CallActionReceiver.ACTION_ANSWER, 2),
            )
        } else {
            Notification.CallStyle.forOngoingCall(
                person,
                actionIntent(call.id, CallActionReceiver.ACTION_HANG_UP, 3),
            )
        }
        val silent = call.silentRingingRequested || call.id in silenced
        val channel = when {
            !call.isRinging -> ONGOING_CHANNEL
            silent -> SILENT_INCOMING_CHANNEL
            else -> INCOMING_CHANNEL
        }
        val builder = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle(call.displayName)
            .setContentText(notificationText(call, assistantState, risk))
            .setContentIntent(content)
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(!call.isRinging)
            .setStyle(style)
        if (!call.isRinging && assistantState?.aiHandling == true) {
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_phone),
                    "Take over",
                    actionIntent(call.id, CallActionReceiver.ACTION_TAKE_OVER, 4),
                ).build(),
            )
        }
        if (call.isRinging) builder.setFullScreenIntent(content, true)
        val notification = builder.build()
        if (call.isRinging && !silent) {
            notification.flags = notification.flags or Notification.FLAG_INSISTENT
        }
        manager?.notify(notificationId(call.id), notification)
        shown.add(call.id)
    }

    private fun notificationText(
        call: CallUiState,
        assistantState: AssistantCallUiState?,
        risk: RiskUiState?,
    ): String = when {
        call.isRinging -> "Incoming call"
        assistantState?.aiHandling == true && risk != null ->
            "AI receptionist · ${risk.label.headline}"
        assistantState?.aiHandling == true -> "AI receptionist is handling this call"
        risk != null -> "Ongoing call · ${risk.label.headline}"
        else -> "Ongoing call"
    }

    private fun actionIntent(callId: String, action: String, salt: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            notificationId(callId) xor salt,
            Intent(context, CallActionReceiver::class.java)
                .setAction(action)
                .putExtra(CallActionReceiver.EXTRA_CALL_ID, callId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun notificationId(callId: String): Int = 0x41000000 xor callId.hashCode()

    private companion object {
        const val INCOMING_CHANNEL = "incoming_calls_v1"
        const val SILENT_INCOMING_CHANNEL = "incoming_calls_silent_v1"
        const val ONGOING_CHANNEL = "ongoing_calls_v1"
    }
}
