package com.aios.phone.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aios.phone.PhoneRuntime
import com.aios.phone.model.PhoneAction

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: return
        when (intent.action) {
            ACTION_ANSWER -> PhoneRuntime.dispatch(PhoneAction.Answer(callId))
            ACTION_DECLINE -> PhoneRuntime.dispatch(PhoneAction.Reject(callId))
            ACTION_HANG_UP -> PhoneRuntime.dispatch(PhoneAction.Disconnect(callId))
            ACTION_TAKE_OVER -> PhoneRuntime.dispatch(PhoneAction.TakeOver(callId))
        }
    }

    companion object {
        const val EXTRA_CALL_ID = "call_id"
        const val ACTION_ANSWER = "com.aios.phone.action.ANSWER"
        const val ACTION_DECLINE = "com.aios.phone.action.DECLINE"
        const val ACTION_HANG_UP = "com.aios.phone.action.HANG_UP"
        const val ACTION_TAKE_OVER = "com.aios.phone.action.TAKE_OVER"
    }
}
