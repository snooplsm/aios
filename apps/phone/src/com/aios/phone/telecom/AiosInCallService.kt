package com.aios.phone.telecom

import android.app.Notification
import android.content.pm.ServiceInfo
import android.os.OutcomeReceiver
import android.telecom.Call
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.InCallService
import android.util.Log
import com.aios.phone.PhoneRuntime

class AiosInCallService : InCallService() {
    override fun onCreate() {
        super.onCreate()
        PhoneRuntime.attachTelecom(this)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        PhoneRuntime.onCallAdded(call)
    }

    override fun onCallRemoved(call: Call) {
        PhoneRuntime.onCallRemoved(call)
        super.onCallRemoved(call)
    }

    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        PhoneRuntime.onCurrentEndpointChanged(callEndpoint)
    }

    override fun onAvailableCallEndpointsChanged(availableEndpoints: List<CallEndpoint>) {
        PhoneRuntime.onAvailableEndpointsChanged(availableEndpoints)
    }

    override fun onMuteStateChanged(isMuted: Boolean) {
        PhoneRuntime.onMuteChanged(isMuted)
    }

    override fun onSilenceRinger() {
        PhoneRuntime.onSilenceRinger()
    }

    fun requestEndpoint(endpoint: CallEndpoint) {
        requestCallEndpointChange(
            endpoint,
            mainExecutor,
            object : OutcomeReceiver<Void, CallEndpointException> {
                override fun onResult(result: Void?) = Unit
                override fun onError(error: CallEndpointException) {
                    PhoneRuntime.showMessage("Could not change the call audio route")
                }
            },
        )
    }

    /**
     * Android 16 rejects ongoing CallStyle notifications that are not owned by
     * a phone-call foreground service. Telecom already owns this bound service;
     * promoting it keeps notification enforcement out of the call-state path.
     */
    fun promoteCallNotification(notificationId: Int, notification: Notification): Boolean {
        return try {
            startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL,
            )
            true
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not promote the ongoing call notification", error)
            false
        }
    }

    fun releaseCallNotification() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        releaseCallNotification()
        PhoneRuntime.detachTelecom(this)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "AiosInCallService"
    }
}
