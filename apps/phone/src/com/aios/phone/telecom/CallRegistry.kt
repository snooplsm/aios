package com.aios.phone.telecom

import android.net.Uri
import android.telecom.Call
import android.telecom.CallEndpoint
import android.telecom.InCallService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.view.Surface
import com.aios.phone.model.AudioEndpointUiState
import com.aios.phone.model.CallUiState
import java.util.IdentityHashMap
import java.util.UUID

/**
 * Main-thread-owned map between framework Call objects and stable, opaque IDs.
 * Call objects never escape this boundary into UI state.
 */
class CallRegistry(
    private val onChanged: () -> Unit,
    private val accountSupportsRtt: (PhoneAccountHandle?) -> Boolean,
    private val onRttSessionChanged: (callId: String, rttCall: Call.RttCall?) -> Unit,
    private val onRttFailure: (callId: String) -> Unit,
    private val onVideoFailure: (callId: String) -> Unit,
) {
    private val callsById = linkedMapOf<String, Call>()
    private val idsByCall = IdentityHashMap<Call, String>()
    private val callbacks = IdentityHashMap<Call, Call.Callback>()
    private val endpointsById = linkedMapOf<String, CallEndpoint>()
    private val postDialWaits = IdentityHashMap<Call, Boolean>()
    private val pendingRttRequests = IdentityHashMap<Call, Int>()
    private val rttModes = IdentityHashMap<Call, Int>()
    private val pendingVideoRequests = IdentityHashMap<Call, VideoProfile>()
    private val videoCallbacks = IdentityHashMap<Call, VideoCallbackRecord>()
    private val videoPeerSizes = IdentityHashMap<Call, Pair<Int, Int>>()
    private val videoQualities = IdentityHashMap<Call, Int>()
    private val displaySurfaces = mutableMapOf<String, Surface>()
    private val previewSurfaces = mutableMapOf<String, Surface>()

    var selectedCallId: String? = null
        private set
    var currentEndpointId: String? = null
        private set
    var muted: Boolean = false
        private set

    fun add(call: Call): String {
        idsByCall[call]?.let { return it }
        val id = "call-${UUID.randomUUID()}"
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                selectedCallId = CallSelectionPolicy.afterStateChanged(
                    currentSelection = selectedCallId,
                    changedCallId = id(call),
                    changedCallIsRinging = state == Call.STATE_RINGING,
                )
                onChanged()
            }
            override fun onDetailsChanged(call: Call, details: Call.Details) = onChanged()
            override fun onParentChanged(call: Call, parent: Call?) = onChanged()
            override fun onChildrenChanged(call: Call, children: List<Call>) = onChanged()
            override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: List<Call>) =
                onChanged()
            override fun onPostDialWait(call: Call, remainingPostDialSequence: String) {
                postDialWaits[call] = true
                selectedCallId = CallSelectionPolicy.forOwnerPrompt(selectedCallId, id(call))
                onChanged()
            }
            override fun onRttStatusChanged(
                call: Call,
                enabled: Boolean,
                rttCall: Call.RttCall?,
            ) {
                pendingRttRequests.remove(call)
                if (enabled && rttCall != null) {
                    rttModes[call] = rttCall.rttAudioMode
                } else {
                    rttModes.remove(call)
                }
                id(call)?.let { onRttSessionChanged(it, rttCall.takeIf { enabled }) }
                onChanged()
            }
            override fun onRttModeChanged(call: Call, mode: Int) {
                rttModes[call] = mode
                onChanged()
            }
            override fun onRttRequest(call: Call, id: Int) {
                pendingRttRequests[call] = id
                onChanged()
            }
            override fun onRttInitiationFailure(call: Call, reason: Int) {
                id(call)?.let(onRttFailure)
                onChanged()
            }
            override fun onVideoCallChanged(
                call: Call,
                videoCall: InCallService.VideoCall?,
            ) {
                attachVideoCallback(call, videoCall)
                onChanged()
            }
            override fun onCallDestroyed(call: Call) {
                // InCallService.onCallRemoved is the canonical terminal event;
                // removing here would lose the ID before artifact cleanup runs.
                onChanged()
            }
        }
        callsById[id] = call
        idsByCall[call] = id
        callbacks[call] = callback
        call.registerCallback(callback)
        attachVideoCallback(call, call.videoCall)
        val previousSelection = selectedCallId
        selectedCallId = CallSelectionPolicy.afterCallAdded(
            currentSelection = previousSelection,
            newCallId = id,
            newCallIsRinging = call.details.state == Call.STATE_RINGING,
            currentSelectionStillPresent = previousSelection != null &&
                callsById.containsKey(previousSelection),
        )
        onChanged()
        call.rttCall?.let {
            rttModes[call] = it.rttAudioMode
            onRttSessionChanged(id, it)
        }
        return id
    }

    fun remove(call: Call): String? {
        val id = idsByCall.remove(call) ?: return null
        callbacks.remove(call)?.let(call::unregisterCallback)
        detachVideoCallback(call)
        onRttSessionChanged(id, null)
        postDialWaits.remove(call)
        pendingRttRequests.remove(call)
        rttModes.remove(call)
        pendingVideoRequests.remove(call)
        videoPeerSizes.remove(call)
        videoQualities.remove(call)
        displaySurfaces.remove(id)
        previewSurfaces.remove(id)
        callsById.remove(id)
        selectedCallId = chooseSelected(selectedCallId.takeUnless { it == id })
        onChanged()
        return id
    }

    fun select(callId: String) {
        if (callsById.containsKey(callId)) {
            selectedCallId = callId
            onChanged()
        }
    }

    fun call(callId: String): Call? = callsById[callId]
    fun id(call: Call?): String? = call?.let(idsByCall::get)
    fun callEntries(): List<Pair<String, Call>> = callsById.map { it.key to it.value }

    fun snapshots(): List<CallUiState> = callsById.map { (id, call) ->
        val details = call.details
        val address = displayAddress(details.handle, details.handlePresentation)
        val callerNameAllowed = details.callerDisplayNamePresentation ==
            TelecomManager.PRESENTATION_ALLOWED
        CallUiState(
            id = id,
            displayName = if (details.handlePresentation ==
                TelecomManager.PRESENTATION_ALLOWED) {
                details.contactDisplayName?.toString()?.takeIf(String::isNotBlank)
                    ?: details.callerDisplayName?.toString()
                        ?.takeIf { callerNameAllowed && it.isNotBlank() }
                    ?: address
            } else {
                address
            },
            address = address,
            state = details.state,
            direction = details.callDirection,
            capabilities = details.callCapabilities,
            properties = details.callProperties,
            videoState = details.videoState,
            connectTimeMillis = details.connectTimeMillis,
            parentId = id(call.parent),
            childIds = call.children.mapNotNull(::id),
            conferenceableIds = call.conferenceableCalls.mapNotNull(::id),
            hasPostDialWait = postDialWaits[call] == true,
            silentRingingRequested = details.extras
                ?.getBoolean(Call.EXTRA_SILENT_RINGING_REQUESTED, false) == true,
            canRequestRtt = accountSupportsRtt(details.accountHandle),
            rttActive = call.isRttActive,
            rttMode = rttModes[call] ?: call.rttCall?.rttAudioMode
                ?: 0,
            hasPendingRttRequest = pendingRttRequests.containsKey(call),
            pendingVideoState = pendingVideoRequests[call]?.videoState,
            videoPeerWidth = videoPeerSizes[call]?.first ?: 0,
            videoPeerHeight = videoPeerSizes[call]?.second ?: 0,
            videoQuality = videoQualities[call] ?: 0,
        )
    }.sortedWith(compareBy<CallUiState> { statePriority(it.state) }.thenBy { it.id })

    fun updateEndpoints(endpoints: List<CallEndpoint>) {
        endpointsById.clear()
        endpoints.forEach { endpoint -> endpointsById[endpoint.identifier.toString()] = endpoint }
        if (currentEndpointId !in endpointsById) currentEndpointId = null
        onChanged()
    }

    fun updateCurrentEndpoint(endpoint: CallEndpoint) {
        val id = endpoint.identifier.toString()
        endpointsById[id] = endpoint
        currentEndpointId = id
        onChanged()
    }

    fun endpoint(id: String): CallEndpoint? = endpointsById[id]

    fun clearPostDialWait(callId: String) {
        callsById[callId]?.let(postDialWaits::remove)
        onChanged()
    }

    fun respondToRttRequest(callId: String, accept: Boolean) {
        val call = callsById[callId] ?: return
        val requestId = pendingRttRequests.remove(call) ?: return
        call.respondToRttRequest(requestId, accept)
        onChanged()
    }

    fun requestVideoState(callId: String, videoState: Int, cameraId: String?) {
        val call = callsById[callId] ?: return
        val videoCall = call.videoCall ?: return
        videoCall.setCamera(if (VideoProfile.isTransmissionEnabled(videoState)) cameraId else null)
        videoCall.sendSessionModifyRequest(VideoProfile(videoState))
    }

    fun respondToVideoRequest(callId: String, accept: Boolean, cameraId: String?) {
        val call = callsById[callId] ?: return
        val requested = pendingVideoRequests.remove(call) ?: return
        val response = if (accept) requested else VideoProfile(call.details.videoState)
        val videoCall = call.videoCall ?: return
        if (accept && VideoProfile.isTransmissionEnabled(response.videoState)) {
            videoCall.setCamera(cameraId)
        }
        videoCall.sendSessionModifyResponse(response)
        onChanged()
    }

    fun setVideoDisplaySurface(callId: String, surface: Surface?) {
        if (surface == null) displaySurfaces.remove(callId) else displaySurfaces[callId] = surface
        callsById[callId]?.videoCall?.setDisplaySurface(surface)
    }

    fun setVideoPreviewSurface(callId: String, surface: Surface?) {
        if (surface == null) previewSurfaces.remove(callId) else previewSurfaces[callId] = surface
        callsById[callId]?.videoCall?.setPreviewSurface(surface)
    }

    fun setVideoOrientation(callId: String, degrees: Int) {
        callsById[callId]?.videoCall?.setDeviceOrientation(degrees)
    }

    fun endpointSnapshots(): List<AudioEndpointUiState> = endpointsById.map { (id, endpoint) ->
        AudioEndpointUiState(id, endpoint.endpointName.toString(), endpoint.endpointType)
    }

    fun updateMuted(value: Boolean) {
        muted = value
        onChanged()
    }

    fun clear() {
        callsById.keys.toList().forEach { id ->
            callsById[id]?.let(::detachVideoCallback)
            onRttSessionChanged(id, null)
        }
        callbacks.forEach { (call, callback) -> call.unregisterCallback(callback) }
        callbacks.clear()
        idsByCall.clear()
        callsById.clear()
        endpointsById.clear()
        postDialWaits.clear()
        pendingRttRequests.clear()
        rttModes.clear()
        pendingVideoRequests.clear()
        videoPeerSizes.clear()
        videoQualities.clear()
        displaySurfaces.clear()
        previewSurfaces.clear()
        selectedCallId = null
        currentEndpointId = null
        muted = false
        onChanged()
    }

    private fun chooseSelected(candidate: String?): String? {
        if (candidate != null && callsById.containsKey(candidate)) return candidate
        return snapshots().firstOrNull()?.id
    }

    private fun displayAddress(uri: Uri?, presentation: Int): String {
        if (presentation != TelecomManager.PRESENTATION_ALLOWED) {
            return when (presentation) {
                TelecomManager.PRESENTATION_RESTRICTED -> "Private number"
                TelecomManager.PRESENTATION_PAYPHONE -> "Payphone"
                TelecomManager.PRESENTATION_UNAVAILABLE -> "Unavailable number"
                else -> "Unknown caller"
            }
        }
        val value = uri?.schemeSpecificPart.orEmpty()
        if (value.isBlank()) return "Unknown caller"
        return value.take(80)
    }

    private fun attachVideoCallback(call: Call, videoCall: InCallService.VideoCall?) {
        detachVideoCallback(call)
        if (videoCall == null) return
        val callback = object : InCallService.VideoCall.Callback() {
            override fun onSessionModifyRequestReceived(videoProfile: VideoProfile) {
                pendingVideoRequests[call] = videoProfile
                onChanged()
            }

            override fun onSessionModifyResponseReceived(
                status: Int,
                requestedProfile: VideoProfile,
                responseProfile: VideoProfile,
            ) {
                if (status != android.telecom.Connection.VideoProvider
                        .SESSION_MODIFY_REQUEST_SUCCESS) {
                    id(call)?.let(onVideoFailure)
                }
                onChanged()
            }

            override fun onCallSessionEvent(event: Int) = Unit

            override fun onPeerDimensionsChanged(width: Int, height: Int) {
                videoPeerSizes[call] = width.coerceAtLeast(0) to height.coerceAtLeast(0)
                onChanged()
            }

            override fun onVideoQualityChanged(videoQuality: Int) {
                videoQualities[call] = videoQuality
                onChanged()
            }

            override fun onCallDataUsageChanged(dataUsage: Long) = Unit

            override fun onCameraCapabilitiesChanged(
                cameraCapabilities: VideoProfile.CameraCapabilities,
            ) = Unit
        }
        videoCall.registerCallback(callback)
        videoCallbacks[call] = VideoCallbackRecord(videoCall, callback)
        id(call)?.let { callId ->
            videoCall.setDisplaySurface(displaySurfaces[callId])
            videoCall.setPreviewSurface(previewSurfaces[callId])
        }
    }

    private fun detachVideoCallback(call: Call) {
        videoCallbacks.remove(call)?.let { record ->
            record.videoCall.unregisterCallback(record.callback)
        }
    }

    private data class VideoCallbackRecord(
        val videoCall: InCallService.VideoCall,
        val callback: InCallService.VideoCall.Callback,
    )

    private fun statePriority(state: Int): Int = when (state) {
        Call.STATE_RINGING -> 0
        Call.STATE_ACTIVE -> 1
        Call.STATE_DIALING, Call.STATE_CONNECTING -> 2
        Call.STATE_HOLDING -> 3
        else -> 4
    }
}
