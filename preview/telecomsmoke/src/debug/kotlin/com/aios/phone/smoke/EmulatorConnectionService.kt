package com.aios.phone.smoke

import android.content.ComponentName
import android.net.Uri
import android.telecom.Conference
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Supplies a managed synthetic call to Android Telecom for emulator testing. */
class EmulatorConnectionService : ConnectionService() {
    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection {
        val address = request.address
            ?: Uri.fromParts(PhoneAccount.SCHEME_TEL, EmulatorCallActivity.DEFAULT_NUMBER, null)
        return EmulatorConnection(address, incoming = true).also {
            fixtureConnections += it
            refreshConferenceableConnections()
        }
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest,
    ): Connection {
        val address = request.address
            ?: Uri.fromParts(PhoneAccount.SCHEME_TEL, EmulatorCallActivity.DEFAULT_NUMBER, null)
        return EmulatorConnection(address, incoming = false).also {
            fixtureConnections += it
            refreshConferenceableConnections()
        }
    }

    override fun onConference(connection1: Connection, connection2: Connection) {
        val first = connection1 as? EmulatorConnection ?: return
        val second = connection2 as? EmulatorConnection ?: return
        if (first === second || first.conference != null || second.conference != null) return

        val conference = EmulatorConference(
            PhoneAccountHandle(
                ComponentName(this, EmulatorConnectionService::class.java),
                EmulatorCallActivity.ACCOUNT_ID,
            ),
        )
        conference.addConnection(first)
        conference.addConnection(second)
        first.setConferenceChild(true)
        second.setConferenceChild(true)
        conference.setActive()
        fixtureConferences += conference
        fixtureEvents += "conference"
        addConference(conference)
        refreshConferenceableConnections()
    }

    private class EmulatorConnection(address: Uri, incoming: Boolean) : Connection() {
        init {
            setAddress(address, TelecomManager.PRESENTATION_ALLOWED)
            setCallerDisplayName(
                if (incoming) "AIOS Emulator Caller" else "AIOS Emulator Destination",
                TelecomManager.PRESENTATION_ALLOWED,
            )
            connectionCapabilities = CAPABILITY_HOLD or CAPABILITY_SUPPORT_HOLD
            audioModeIsVoip = true
            if (incoming) setRinging() else setDialing()
        }

        override fun onAnswer() {
            setActive()
            refreshConferenceableConnections()
        }

        override fun onReject() = end(DisconnectCause.REJECTED)

        override fun onDisconnect() = end(DisconnectCause.LOCAL)

        override fun onAbort() = end(DisconnectCause.CANCELED)

        override fun onHold() {
            setOnHold()
            refreshConferenceableConnections()
        }

        override fun onUnhold() {
            setActive()
            refreshConferenceableConnections()
        }

        override fun onPlayDtmfTone(c: Char) {
            fixtureEvents += "play:$c"
        }

        override fun onStopDtmfTone() {
            fixtureEvents += "stop"
        }

        fun end(code: Int) {
            setDisconnected(DisconnectCause(code))
            destroy()
            fixtureConnections -= this
            refreshConferenceableConnections()
        }

        fun activate() {
            setActive()
            refreshConferenceableConnections()
        }

        fun setConferenceChild(value: Boolean) {
            connectionCapabilities = if (value) {
                connectionCapabilities or CAPABILITY_SEPARATE_FROM_CONFERENCE
            } else {
                connectionCapabilities and CAPABILITY_SEPARATE_FROM_CONFERENCE.inv()
            }
        }
    }

    private class EmulatorConference(
        phoneAccount: PhoneAccountHandle,
    ) : Conference(phoneAccount) {
        init {
            connectionCapabilities = Connection.CAPABILITY_HOLD or
                Connection.CAPABILITY_SUPPORT_HOLD or
                Connection.CAPABILITY_MANAGE_CONFERENCE
        }

        override fun onDisconnect() {
            connections.toList().forEach { connection ->
                (connection as? EmulatorConnection)?.end(DisconnectCause.LOCAL)
            }
            end(DisconnectCause.LOCAL)
        }

        override fun onHold() = setOnHold()

        override fun onUnhold() = setActive()

        override fun onSeparate(connection: Connection) {
            fixtureEvents += "separate"
            removeConnection(connection)
            (connection as? EmulatorConnection)?.setConferenceChild(false)
            connection.setActive()
            if (connections.size <= 1) {
                connections.toList().forEach { remaining ->
                    removeConnection(remaining)
                    (remaining as? EmulatorConnection)?.setConferenceChild(false)
                    remaining.setOnHold()
                }
                end(DisconnectCause.LOCAL)
            }
            refreshConferenceableConnections()
        }

        fun end(code: Int) {
            setDisconnected(DisconnectCause(code))
            destroy()
            fixtureConferences -= this
            refreshConferenceableConnections()
        }
    }

    companion object {
        private val fixtureConnections = Collections.newSetFromMap(
            ConcurrentHashMap<EmulatorConnection, Boolean>(),
        )
        private val fixtureConferences = Collections.newSetFromMap(
            ConcurrentHashMap<EmulatorConference, Boolean>(),
        )
        private val fixtureEvents = CopyOnWriteArrayList<String>()

        fun disconnectAll() {
            fixtureConferences.toList().forEach { it.end(DisconnectCause.LOCAL) }
            fixtureConnections.toList().forEach { it.end(DisconnectCause.LOCAL) }
        }

        fun activateAll() = fixtureConnections.toList().forEach(EmulatorConnection::activate)

        fun resetAudit() = fixtureEvents.clear()

        fun auditSnapshot(): String = fixtureEvents.joinToString(separator = "\n", postfix = "\n")

        private fun refreshConferenceableConnections() {
            val eligible = fixtureConnections.filter { connection ->
                connection.conference == null &&
                    connection.state in setOf(Connection.STATE_ACTIVE, Connection.STATE_HOLDING)
            }
            fixtureConnections.forEach { connection ->
                connection.setConferenceableConnections(
                    if (connection in eligible) eligible.filterNot { it === connection }
                    else emptyList(),
                )
            }
        }
    }
}
