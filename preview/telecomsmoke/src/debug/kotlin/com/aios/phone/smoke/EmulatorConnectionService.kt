package com.aios.phone.smoke

import android.net.Uri
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** Supplies a managed synthetic call to Android Telecom for emulator testing. */
class EmulatorConnectionService : ConnectionService() {
    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection {
        val address = request.address
            ?: Uri.fromParts(PhoneAccount.SCHEME_TEL, EmulatorCallActivity.DEFAULT_NUMBER, null)
        return EmulatorConnection(address).also { connections += it }
    }

    private class EmulatorConnection(address: Uri) : Connection() {
        init {
            setAddress(address, TelecomManager.PRESENTATION_ALLOWED)
            setCallerDisplayName("AIOS Emulator Caller", TelecomManager.PRESENTATION_ALLOWED)
            connectionCapabilities = CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD
            audioModeIsVoip = true
            setRinging()
        }

        override fun onAnswer() = setActive()

        override fun onReject() = end(DisconnectCause.REJECTED)

        override fun onDisconnect() = end(DisconnectCause.LOCAL)

        override fun onAbort() = end(DisconnectCause.CANCELED)

        override fun onHold() = setOnHold()

        override fun onUnhold() = setActive()

        fun end(code: Int) {
            setDisconnected(DisconnectCause(code))
            destroy()
            connections -= this
        }
    }

    companion object {
        private val connections = Collections.newSetFromMap(
            ConcurrentHashMap<EmulatorConnection, Boolean>(),
        )

        fun disconnectAll() = connections.toList().forEach { it.end(DisconnectCause.LOCAL) }
    }
}
