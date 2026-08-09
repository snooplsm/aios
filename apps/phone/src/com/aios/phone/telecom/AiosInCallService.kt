package com.aios.phone.telecom

import android.os.OutcomeReceiver
import android.telecom.Call
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.InCallService
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

    override fun onDestroy() {
        PhoneRuntime.detachTelecom(this)
        super.onDestroy()
    }
}
