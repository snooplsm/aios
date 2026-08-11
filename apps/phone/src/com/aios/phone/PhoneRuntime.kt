package com.aios.phone

import android.annotation.SuppressLint
import android.Manifest
import android.app.Application
import android.app.AppOpsManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallEndpoint
import android.telecom.PhoneAccountHandle
import android.telecom.PhoneAccountSuggestion
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.telephony.PhoneNumberUtils
import androidx.core.content.edit
import com.aios.phone.context.CallEventContextClient
import com.aios.phone.data.CallHistoryRepository
import com.aios.phone.data.VoicemailRepository
import com.aios.phone.intelligence.CallAssistantClient
import com.aios.phone.model.PhoneAction
import com.aios.phone.model.PhoneUiState
import com.aios.phone.model.PhoneAccountUiState
import com.aios.phone.model.AssistantPolicyUiState
import com.aios.phone.model.AssistantPolicySemantics
import com.aios.phone.model.AssistantCallSemantics
import com.aios.phone.model.AssistantCallUiState
import com.aios.phone.model.CallRiskSemantics
import com.aios.phone.model.RiskUiState
import com.aios.phone.model.RttUiState
import com.aios.phone.model.ThemePreference
import com.aios.phone.model.TranscriptUiState
import com.aios.phone.model.VoicemailPlaybackState
import com.aios.phone.notifications.CallNotificationCoordinator
import com.aios.phone.telecom.AiosInCallService
import com.aios.phone.telecom.CallRegistry
import com.aios.phone.telecom.ProximityLockController
import com.aios.phone.telecom.RttSessionController
import com.aios.phone.telecom.VoicemailPlaybackController
import com.aios.phone.ui.InCallActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** Single process UDF store. All Telecom mutations are serialized on the main thread. */
object PhoneRuntime {
    private const val PREFS = "phone_ui"
    private const val THEME = "theme"
    private const val SHOW_DIALER_ROLE_PROMPT = "show_dialer_role_prompt"

    private val main = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(PhoneUiState())
    val state: StateFlow<PhoneUiState> = mutableState.asStateFlow()

    private lateinit var application: Application
    private lateinit var calls: CallRegistry
    @SuppressLint("StaticFieldLeak") // Both owners receive only the Application context.
    private lateinit var notifications: CallNotificationCoordinator
    @SuppressLint("StaticFieldLeak") // Both owners receive only the Application context.
    private lateinit var assistant: CallAssistantClient
    private lateinit var proximity: ProximityLockController
    private lateinit var rtt: RttSessionController
    @SuppressLint("StaticFieldLeak") // Repository receives only the Application context.
    private lateinit var history: CallHistoryRepository
    @SuppressLint("StaticFieldLeak") // Client receives only the Application context.
    private lateinit var contextEvents: CallEventContextClient
    @SuppressLint("StaticFieldLeak") // Repository receives only the Application context.
    private lateinit var voicemailRepository: VoicemailRepository
    @SuppressLint("StaticFieldLeak") // Controller receives only the Application context.
    private lateinit var voicemailPlayback: VoicemailPlaybackController
    private val markedVoicemails = mutableSetOf<String>()
    private val dtmfStops = mutableMapOf<String, Runnable>()
    private val accountIds = linkedMapOf<PhoneAccountHandle, String>()
    private val accountsById = linkedMapOf<String, PhoneAccountHandle>()
    private var telecomService: AiosInCallService? = null
    private var initialized = false
    private val callLogOpChanged = AppOpsManager.OnOpChangedListener { operation, packageName ->
        if (operation == AppOpsManager.OPSTR_READ_CALL_LOG &&
            packageName == application.packageName) refreshRole()
    }

    fun initialize(value: Application) {
        if (initialized) return
        application = value
        rtt = RttSessionController(
            onRemoteText = { callId, chunk ->
                reduce { current ->
                    val conversation = current.rttConversations[callId] ?: RttUiState()
                    current.copy(
                        rttConversations = current.rttConversations + (
                            callId to conversation.copy(
                                remoteText = (conversation.remoteText + chunk)
                                    .takeLast(MAX_RTT_TRANSCRIPT_CHARS),
                                error = null,
                            )
                        ),
                    )
                }
            },
            onError = { callId ->
                reduce { current ->
                    val conversation = current.rttConversations[callId] ?: RttUiState()
                    current.copy(
                        rttConversations = current.rttConversations + (
                            callId to conversation.copy(error = "RTT text channel is unavailable")
                        ),
                    )
                }
            },
        )
        calls = CallRegistry(
            onChanged = ::publish,
            accountSupportsRtt = ::accountSupportsRtt,
            onRttSessionChanged = ::onRttSessionChanged,
            onRttFailure = { showMessage("The network could not start RTT") },
            onVideoFailure = { showMessage("The video-call change was not accepted") },
        )
        notifications = CallNotificationCoordinator(value)
        proximity = ProximityLockController(value)
        history = CallHistoryRepository(value) { result ->
            result.fold(
                onSuccess = { recentCalls ->
                    reduce {
                        it.copy(
                            recentCalls = recentCalls,
                            recentCallsLoading = false,
                            recentCallsError = null,
                        )
                    }
                },
                onFailure = {
                    reduce {
                        it.copy(
                            recentCallsLoading = false,
                            recentCallsError = "Call history is available after AIOS Phone is chosen as the calling app",
                        )
                    }
                },
            )
        }
        contextEvents = CallEventContextClient(value)
        voicemailRepository = VoicemailRepository(value) { result ->
            result.fold(
                onSuccess = { voicemails ->
                    markedVoicemails.retainAll(voicemails.mapTo(mutableSetOf()) { it.id })
                    reduce {
                        it.copy(
                            voicemails = voicemails,
                            voicemailsLoading = false,
                            voicemailsError = null,
                        )
                    }
                },
                onFailure = {
                    reduce {
                        it.copy(
                            voicemailsLoading = false,
                            voicemailsError = "Voicemail is available after AIOS Phone is chosen as the calling app",
                        )
                    }
                },
            )
        }
        voicemailPlayback = VoicemailPlaybackController(
            context = value,
            onState = { voicemailId, playbackState ->
                reduce {
                    it.copy(
                        voicemailPlaybackId = voicemailId,
                        voicemailPlaybackState = playbackState,
                    )
                }
                if (voicemailId != null && playbackState == VoicemailPlaybackState.PLAYING &&
                    markedVoicemails.add(voicemailId)) {
                    voicemailRepository.markRead(voicemailId)
                }
            },
            onError = { showMessage("This voicemail audio could not be played") },
        )
        assistant = CallAssistantClient(value, object : CallAssistantClient.Callbacks {
            override fun onAssistantConnectionChanged(connected: Boolean) {
                reduce {
                    it.copy(
                        assistantConnected = connected,
                        assistantPolicy = if (connected) {
                            it.assistantPolicy
                        } else {
                            it.assistantPolicy.copy(
                                available = false,
                                loading = false,
                                saving = false,
                                error = "Call-assistant service is unavailable",
                            )
                        },
                    )
                }
            }

            override fun onTranscript(callId: String, segment: TranscriptUiState) {
                val current = mutableState.value.transcripts[callId].orEmpty().toMutableList()
                val last = current.lastOrNull()
                if (last != null && !last.isFinal && last.direction == segment.direction) {
                    current[current.lastIndex] = segment
                } else {
                    current.add(segment)
                }
                reduce {
                    it.copy(transcripts = it.transcripts +
                        (callId to current.takeLast(MAX_TRANSCRIPT_SEGMENTS)))
                }
            }

            override fun onRisk(callId: String, risk: RiskUiState) {
                reduce { current ->
                    if (!CallRiskSemantics.shouldReplace(
                            current.risks[callId]?.revision,
                            risk.revision,
                        )
                    ) {
                        current
                    } else {
                        current.copy(risks = current.risks + (callId to risk))
                    }
                }
                syncNotifications()
            }

            override fun onAssistantCallState(callId: String, state: AssistantCallUiState) {
                reduce { current ->
                    if (!AssistantCallSemantics.shouldReplace(
                            current.assistantCalls[callId]?.revision,
                            state.revision,
                        )
                    ) {
                        current
                    } else {
                        current.copy(
                            assistantCalls = current.assistantCalls + (callId to state),
                        )
                    }
                }
                syncNotifications()
            }

            override fun onAiAnswerRequested(callId: String) {
                answerWithAi(callId)
            }

            override fun onTakeOverResult(callId: String, succeeded: Boolean) {
                if (succeeded) {
                    showMessage("You are now handling the call. Live transcription continues.")
                } else {
                    showMessage("AI handoff could not be completed")
                }
            }

            override fun onAssistantFailure(callId: String, status: Int, detail: String) {
                val message = when (status) {
                    -1 -> "AI could not access both call-audio directions. The call is connected to you."
                    -2, -3, -6, -9 ->
                        "AI call storage failed. The phone call is still connected."
                    -4 -> "AI answering became unavailable. The call is connected to you."
                    -5 -> "AI could not generate a reply. The call is connected to you."
                    -7 -> "AI could not speak to the caller. The call is connected to you."
                    else -> "The call assistant stopped. The phone call is still connected."
                }
                showMessage(message)
            }

            override fun onPolicyChanged(policy: AssistantPolicyUiState) {
                reduce { it.copy(assistantPolicy = policy) }
            }
        })
        initialized = true
        value.getSystemService(AppOpsManager::class.java)?.startWatchingMode(
            AppOpsManager.OPSTR_READ_CALL_LOG,
            value.packageName,
            callLogOpChanged,
        )
        val preferences = value.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedTheme = preferences.getString(THEME, ThemePreference.SYSTEM.name)
        val theme = runCatching { ThemePreference.valueOf(savedTheme.orEmpty()) }
            .getOrDefault(ThemePreference.SYSTEM)
        mutableState.value = mutableState.value.copy(
            themePreference = theme,
            showDialerRolePrompt = preferences.getBoolean(SHOW_DIALER_ROLE_PROMPT, true),
        )
        assistant.start()
        refreshRole()
    }

    fun attachTelecom(service: AiosInCallService) = onMain {
        telecomService = service
        publish()
    }

    fun detachTelecom(service: AiosInCallService) = onMain {
        if (telecomService === service) {
            telecomService = null
            dtmfStops.keys.toList().forEach(::stopDtmf)
            calls.clear()
            publish()
        }
    }

    fun onCallAdded(call: Call) = onMain {
        voicemailPlayback.stop()
        val id = calls.add(call)
        assistant.onCallAdded(id, call)
        publish()
    }

    fun onCallRemoved(call: Call) = onMain {
        val id = calls.id(call)
        if (id != null) {
            stopDtmf(id)
            assistant.onCallRemoved(id, call.details.disconnectCause.code)
            reduce {
                it.copy(
                    transcripts = it.transcripts - id,
                    risks = it.risks - id,
                    assistantCalls = it.assistantCalls - id,
                    rttConversations = it.rttConversations - id,
                    showDtmf = if (it.calls.size <= 1) false else it.showDtmf,
                )
            }
        }
        calls.remove(call)
        contextEvents.onCallLogMayHaveChanged()
        publish()
    }

    fun onAvailableEndpointsChanged(endpoints: List<CallEndpoint>) = onMain {
        calls.updateEndpoints(endpoints)
    }

    fun onCurrentEndpointChanged(endpoint: CallEndpoint) = onMain {
        calls.updateCurrentEndpoint(endpoint)
    }

    fun onMuteChanged(muted: Boolean) = onMain { calls.updateMuted(muted) }

    fun onSilenceRinger() = onMain { notifications.silence(calls.snapshots()) }

    fun dispatch(action: PhoneAction) = onMain {
        when (action) {
            is PhoneAction.ChangeDialInput -> reduce {
                it.copy(dialInput = action.value.take(80), message = null)
            }
            is PhoneAction.ChangeDialWithRtt -> reduce {
                it.copy(dialWithRtt = action.enabled, message = null)
            }
            is PhoneAction.ChangeHomeSection -> reduce {
                it.copy(homeSection = action.section, message = null)
            }
            PhoneAction.PlaceCall -> placeCall(mutableState.value.dialInput, clearInput = true)
            is PhoneAction.DialNumber -> placeCall(action.number, clearInput = false)
            is PhoneAction.MessageNumber -> openMessage(action.number)
            PhoneAction.ReloadRecentCalls -> {
                reduce { it.copy(recentCallsLoading = true, recentCallsError = null) }
                history.reload()
            }
            PhoneAction.ReloadVoicemails -> {
                reduce { it.copy(voicemailsLoading = true, voicemailsError = null) }
                voicemailRepository.reload()
            }
            is PhoneAction.ToggleVoicemail -> toggleVoicemail(action.voicemailId)
            is PhoneAction.FetchVoicemail -> {
                if (voicemailRepository.requestContent(action.voicemailId)) {
                    showMessage("Requesting voicemail audio…")
                } else {
                    showMessage("This voicemail cannot be downloaded")
                }
            }
            is PhoneAction.SelectCall -> calls.select(action.callId)
            is PhoneAction.Answer -> answerCall(action.callId, action.videoState)
            is PhoneAction.ClaimOwnerAnswer -> assistant.cancelAutomaticAnswer(action.callId)
            is PhoneAction.Ignore -> {
                val ringing = mutableState.value.calls.firstOrNull {
                    it.id == action.callId && it.isRinging
                }
                if (ringing != null) notifications.silence(listOf(ringing))
            }
            is PhoneAction.AnswerWithAi -> {
                val policy = mutableState.value.assistantPolicy
                if (!policy.automaticAnswerAvailable
                    || !policy.processingEnabled) {
                    showMessage("AI answering is locked until on-device processing and caller audio are ready")
                } else {
                    answerWithAi(action.callId)
                }
            }
            is PhoneAction.TakeOver -> assistant.takeOver(action.callId)
            is PhoneAction.Reject -> rejectCall(action.callId)
            is PhoneAction.Disconnect -> calls.call(action.callId)?.disconnect()
            is PhoneAction.Hold -> calls.call(action.callId)?.hold()
            is PhoneAction.Unhold -> calls.call(action.callId)?.unhold()
            is PhoneAction.SendDtmf -> sendDtmf(action.callId, action.digit)
            is PhoneAction.StopDtmf -> stopDtmf(action.callId)
            is PhoneAction.Merge -> {
                val first = calls.call(action.callId)
                val second = calls.call(action.otherCallId)
                if (first != null && second != null && first !== second) first.conference(second)
            }
            is PhoneAction.Split -> calls.call(action.callId)?.splitFromConference()
            is PhoneAction.SwapConference -> calls.call(action.callId)?.swapConference()
            is PhoneAction.SetMuted -> telecomService?.setMuted(action.muted)
                ?: showMessage("Call controls are not connected")
            is PhoneAction.SelectEndpoint -> {
                val endpoint = calls.endpoint(action.endpointId)
                val service = telecomService
                if (endpoint != null && service != null) service.requestEndpoint(endpoint)
                else showMessage("That audio route is no longer available")
            }
            is PhoneAction.SelectPhoneAccount -> {
                val call = calls.call(action.callId)
                val account = accountsById[action.accountId]
                if (call?.details?.state == Call.STATE_SELECT_PHONE_ACCOUNT && account != null) {
                    call.phoneAccountSelected(account, false)
                } else {
                    showMessage("That SIM is no longer available")
                }
            }
            is PhoneAction.ContinuePostDial -> {
                calls.call(action.callId)?.postDialContinue(action.proceed)
                calls.clearPostDialWait(action.callId)
            }
            is PhoneAction.RequestRtt -> {
                val call = calls.call(action.callId)
                val snapshot = mutableState.value.calls.firstOrNull { it.id == action.callId }
                if (call != null && snapshot?.canRequestRtt == true && !snapshot.rttActive) {
                    call.sendRttRequest()
                } else {
                    showMessage("RTT is not available for this call")
                }
            }
            is PhoneAction.RespondToRtt -> calls.respondToRttRequest(
                action.callId,
                action.accept,
            )
            is PhoneAction.ChangeRttText -> changeRttText(action.callId, action.value)
            is PhoneAction.StopRtt -> calls.call(action.callId)?.stopRtt()
            is PhoneAction.RequestVideoState -> requestVideoState(
                action.callId,
                action.videoState,
            )
            is PhoneAction.RespondToVideo -> respondToVideoRequest(
                action.callId,
                action.accept,
            )
            is PhoneAction.AttachVideoDisplay -> calls.setVideoDisplaySurface(
                action.callId,
                action.surface,
            )
            is PhoneAction.AttachVideoPreview -> calls.setVideoPreviewSurface(
                action.callId,
                action.surface,
            )
            is PhoneAction.SetVideoOrientation -> calls.setVideoOrientation(
                action.callId,
                action.degrees.takeIf { it in setOf(0, 90, 180, 270) } ?: 0,
            )
            PhoneAction.ToggleDtmf -> reduce { it.copy(showDtmf = !it.showDtmf) }
            is PhoneAction.ChangeTheme -> {
                application.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                    putString(THEME, action.preference.name)
                }
                reduce { it.copy(themePreference = action.preference) }
            }
            is PhoneAction.ChangeDialerRolePromptVisible -> {
                application.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                    putBoolean(SHOW_DIALER_ROLE_PROMPT, action.visible)
                }
                reduce { it.copy(showDialerRolePrompt = action.visible) }
            }
            is PhoneAction.ChangeProcessingEnabled -> updatePolicyDraft {
                it.copy(processingEnabled = action.enabled, error = null)
            }
            is PhoneAction.ChangeAutoAnswerEnabled -> updatePolicyDraft {
                it.copy(
                    answerMode = AssistantPolicySemantics.modeAfterAutoAnswerToggle(
                        it.answerMode,
                        action.enabled,
                    ),
                    error = null,
                )
            }
            is PhoneAction.ChangeAnswerMode -> {
                if (AssistantPolicySemantics.isKnownAnswerMode(action.mode)) {
                    updatePolicyDraft { it.copy(answerMode = action.mode, error = null) }
                }
            }
            is PhoneAction.ChangeAnswerDelayMode -> {
                if (AssistantPolicySemantics.isKnownDirectDelayMode(action.mode)) {
                    updatePolicyDraft { it.copy(answerDelayMode = action.mode, error = null) }
                }
            }
            is PhoneAction.ChangeMissedDelay -> updatePolicyDraft {
                it.copy(
                    missedDelayMillis = AssistantPolicySemantics.clampMissedDelay(action.millis),
                    error = null,
                )
            }
            PhoneAction.SaveAssistantPolicy -> assistant.savePolicy(
                mutableState.value.assistantPolicy,
            )
            PhoneAction.ReloadAssistantPolicy -> assistant.loadPolicy()
            PhoneAction.ClearMessage -> reduce { it.copy(message = null) }
        }
        publish()
    }

    fun showMessage(message: String) = onMain { reduce { it.copy(message = message) } }

    fun refreshRole() = onMain {
        if (!initialized) return@onMain
        val roleManager = application.getSystemService(RoleManager::class.java)
        val held = roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        contextEvents.setEnabled(held)
        reduce { it.copy(isDialerRoleHeld = held) }
    }

    private fun placeCall(number: String, clearInput: Boolean) {
        val input = number.trim()
        if (input.isEmpty() || !input.matches(Regex("[+*#0-9(),; .-]+"))) {
            showMessage("Enter a valid phone number")
            return
        }
        val manager = application.getSystemService(TelecomManager::class.java)
        if (manager == null) {
            showMessage("Telecom is unavailable")
            return
        }
        try {
            val extras = Bundle().apply {
                if (mutableState.value.dialWithRtt) {
                    putBoolean(TelecomManager.EXTRA_START_CALL_WITH_RTT, true)
                }
            }
            manager.placeCall(Uri.fromParts("tel", input, null), extras)
            reduce {
                it.copy(
                    dialInput = if (clearInput) "" else it.dialInput,
                    dialWithRtt = if (clearInput) false else it.dialWithRtt,
                    message = null,
                )
            }
        } catch (_: SecurityException) {
            showMessage("Choose AIOS Phone as your calling app first")
            return
        } catch (_: RuntimeException) {
            showMessage("The call could not be placed")
            return
        }
        runCatching {
            application.startActivity(
                Intent(application, InCallActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ),
            )
        }.onFailure {
            showMessage("Call placed. Tap the active-call card to open controls")
        }
    }

    private fun openMessage(number: String) {
        val normalized = PhoneNumberUtils.normalizeNumber(number)
        if (normalized.isBlank()) {
            showMessage("This call has no messageable phone number")
            return
        }
        val intent = Intent(
            Intent.ACTION_SENDTO,
            Uri.fromParts("smsto", normalized, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { application.startActivity(intent) }
            .onFailure { showMessage("No messaging app is available") }
    }

    @Suppress("DEPRECATION")
    private fun answerWithAi(callId: String) {
        val call = calls.call(callId) ?: return
        if (call.details.state != Call.STATE_RINGING) return
        val details = call.details
        val address = details.handle?.schemeSpecificPart.orEmpty()
        val lastEmergencyCall = details.extras
            ?.getLong(Call.EXTRA_LAST_EMERGENCY_CALLBACK_TIME_MILLIS, 0L)
            ?: 0L
        val emergencyCallback = details.hasProperty(
            Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE,
        ) || (lastEmergencyCall > 0L &&
            System.currentTimeMillis() - lastEmergencyCall in
                0..EMERGENCY_CALLBACK_WINDOW_MILLIS)
        val emergency = details.hasProperty(
            Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL,
        ) || (address.isNotBlank() && runCatching {
            PhoneNumberUtils.isEmergencyNumber(address)
        }.getOrDefault(true))
        if (emergency || emergencyCallback) {
            showMessage("AI answering is unavailable during emergency handling")
            return
        }
        assistant.markAiAnswered(callId)
        call.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    private fun sendDtmf(callId: String, digit: Char) {
        if (digit !in "0123456789*#") return
        val call = calls.call(callId) ?: return
        stopDtmf(callId)
        call.playDtmfTone(digit)
        val stop = Runnable {
            dtmfStops.remove(callId)
            calls.call(callId)?.stopDtmfTone()
        }
        dtmfStops[callId] = stop
        main.postDelayed(stop, DTMF_PULSE_MILLIS)
    }

    private fun onRttSessionChanged(callId: String, rttCall: Call.RttCall?) {
        if (rttCall == null) {
            rtt.detach(callId)
        } else {
            rtt.attach(callId, rttCall)
            reduce { current ->
                current.copy(
                    rttConversations = current.rttConversations + (
                        callId to (current.rttConversations[callId] ?: RttUiState())
                            .copy(error = null)
                    ),
                )
            }
        }
    }

    private fun toggleVoicemail(voicemailId: String) {
        val voicemail = mutableState.value.voicemails.firstOrNull { it.id == voicemailId }
            ?: return
        if (!voicemail.hasContent) {
            showMessage("Download the voicemail before playing it")
            return
        }
        val uri = voicemailRepository.contentUri(voicemailId) ?: run {
            showMessage("This voicemail is no longer available")
            return
        }
        voicemailPlayback.toggle(voicemailId, uri)
    }

    private fun changeRttText(callId: String, requested: String) {
        val call = mutableState.value.calls.firstOrNull { it.id == callId }
        if (call?.rttActive != true) {
            showMessage("RTT is not active")
            return
        }
        val conversation = mutableState.value.rttConversations[callId] ?: RttUiState()
        val value = requested.take(MAX_RTT_COMPOSER_CHARS)
        val old = conversation.localText
        val common = old.zip(value).takeWhile { (left, right) -> left == right }.size
        val payload = buildString {
            repeat(old.length - common) { append('\b') }
            append(value.substring(common))
        }
        if (payload.isNotEmpty()) rtt.write(callId, payload)
        reduce { current ->
            current.copy(
                rttConversations = current.rttConversations + (
                    callId to conversation.copy(localText = value, error = null)
                ),
            )
        }
    }

    private fun requestVideoState(callId: String, videoState: Int) {
        val snapshot = mutableState.value.calls.firstOrNull { it.id == callId }
            ?: return
        val allowedBits = VideoProfile.STATE_TX_ENABLED or VideoProfile.STATE_RX_ENABLED or
            VideoProfile.STATE_PAUSED
        if (videoState and allowedBits != videoState ||
            (VideoProfile.isVideo(videoState) && !snapshot.isVideo && !snapshot.canStartVideo)) {
            showMessage("Video is not available for this call")
            return
        }
        val cameraId = if (VideoProfile.isTransmissionEnabled(videoState)) {
            frontCameraId() ?: run {
                showMessage("Camera access is required to send video")
                return
            }
        } else {
            null
        }
        calls.requestVideoState(callId, videoState, cameraId)
    }

    private fun answerCall(callId: String, videoState: Int) {
        val call = calls.call(callId)
            ?.takeIf { it.details.state == Call.STATE_RINGING } ?: return
        assistant.cancelAutomaticAnswer(callId)
        val requestedState = videoState.takeIf {
            it == VideoProfile.STATE_AUDIO_ONLY || it == call.details.videoState
        } ?: VideoProfile.STATE_AUDIO_ONLY
        if (VideoProfile.isTransmissionEnabled(requestedState)) {
            val cameraId = frontCameraId() ?: run {
                showMessage("Camera access is required to answer with video")
                return
            }
            call.videoCall?.setCamera(cameraId)
        }
        call.answer(requestedState)
    }

    private fun rejectCall(callId: String) {
        val call = calls.call(callId)
            ?.takeIf { it.details.state == Call.STATE_RINGING } ?: return
        assistant.cancelAutomaticAnswer(callId)
        call.reject(false, null)
    }

    private fun respondToVideoRequest(callId: String, accept: Boolean) {
        val requestedState = mutableState.value.calls.firstOrNull { it.id == callId }
            ?.pendingVideoState ?: return
        val cameraId = if (accept && VideoProfile.isTransmissionEnabled(requestedState)) {
            frontCameraId() ?: run {
                showMessage("Camera access is required to accept two-way video")
                return
            }
        } else {
            null
        }
        calls.respondToVideoRequest(callId, accept, cameraId)
    }

    private fun accountSupportsRtt(handle: PhoneAccountHandle?): Boolean {
        if (handle == null) return false
        val telecom = application.getSystemService(TelecomManager::class.java) ?: return false
        return runCatching {
            telecom.getPhoneAccount(handle)?.hasCapabilities(PhoneAccount.CAPABILITY_RTT) == true
        }.getOrDefault(false)
    }

    private fun frontCameraId(): String? {
        if (application.checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) return null
        val cameras = application.getSystemService(CameraManager::class.java) ?: return null
        return runCatching {
            cameras.cameraIdList.firstOrNull { id ->
                cameras.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: cameras.cameraIdList.firstOrNull()
        }.getOrNull()
    }

    private fun stopDtmf(callId: String) {
        dtmfStops.remove(callId)?.let(main::removeCallbacks)
        calls.call(callId)?.stopDtmfTone()
    }

    private fun publish() {
        if (!initialized) return
        val previous = mutableState.value
        val snapshots = calls.snapshots()
        val phoneAccounts = phoneAccountSnapshots(snapshots)
        mutableState.value = previous.copy(
            calls = snapshots,
            selectedCallId = calls.selectedCallId,
            endpoints = calls.endpointSnapshots(),
            phoneAccounts = phoneAccounts,
            currentEndpointId = calls.currentEndpointId,
            muted = calls.muted,
            telecomConnected = telecomService != null,
        )
        assistant.onCallsChanged(snapshots)
        val activeCall = snapshots.any {
            it.state == Call.STATE_ACTIVE || it.state == Call.STATE_DIALING ||
                it.state == Call.STATE_CONNECTING
        }
        val currentEndpoint = calls.endpointSnapshots().firstOrNull {
            it.id == calls.currentEndpointId
        }
        proximity.update(
            activeCall && currentEndpoint?.type == CallEndpoint.TYPE_EARPIECE,
        )
        syncNotifications()
    }

    private fun syncNotifications() {
        if (!initialized) return
        val current = mutableState.value
        notifications.sync(
            current.calls,
            current.assistantCalls,
            current.risks,
            telecomService,
        )
    }

    private inline fun reduce(block: (PhoneUiState) -> PhoneUiState) {
        mutableState.value = block(mutableState.value)
    }

    private fun phoneAccountSnapshots(snapshots: List<com.aios.phone.model.CallUiState>):
        List<PhoneAccountUiState> {
        val choosing = snapshots.firstOrNull {
            it.state == Call.STATE_SELECT_PHONE_ACCOUNT
        } ?: run {
            accountsById.clear()
            return emptyList()
        }
        val call = calls.call(choosing.id) ?: return emptyList()
        val telecom = application.getSystemService(TelecomManager::class.java)
            ?: return emptyList()
        val suggested = call.details.extras?.getParcelableArrayList(
            Call.EXTRA_SUGGESTED_PHONE_ACCOUNTS,
            PhoneAccountSuggestion::class.java,
        ).orEmpty().map { it.phoneAccountHandle }
        val handles = suggested.ifEmpty {
            try {
                telecom.callCapablePhoneAccounts
            } catch (_: SecurityException) {
                emptyList()
            }
        }.distinct()
        val live = handles.toSet()
        accountIds.keys.retainAll(live)
        accountsById.clear()
        return handles.mapIndexed { index, handle ->
            val id = accountIds.getOrPut(handle) { "account-${UUID.randomUUID()}" }
            accountsById[id] = handle
            val account = runCatching { telecom.getPhoneAccount(handle) }.getOrNull()
            PhoneAccountUiState(
                id = id,
                label = account?.label?.toString()?.takeIf(String::isNotBlank)
                    ?: "SIM ${index + 1}",
            )
        }
    }

    private inline fun updatePolicyDraft(
        block: (AssistantPolicyUiState) -> AssistantPolicyUiState,
    ) {
        reduce { it.copy(assistantPolicy = block(it.assistantPolicy)) }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    private const val MAX_TRANSCRIPT_SEGMENTS = 40
    private const val EMERGENCY_CALLBACK_WINDOW_MILLIS = 5 * 60 * 1000L
    private const val DTMF_PULSE_MILLIS = 180L
    private const val MAX_RTT_COMPOSER_CHARS = 400
    private const val MAX_RTT_TRANSCRIPT_CHARS = 4_000
}
