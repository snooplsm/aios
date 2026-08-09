package com.aios.phone.preview

import android.os.Bundle
import android.telecom.Call
import android.telecom.CallEndpoint
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aios.phone.model.AudioEndpointUiState
import com.aios.phone.model.AssistantPolicyUiState
import com.aios.phone.model.CallUiState
import com.aios.phone.model.HomeSection
import com.aios.phone.model.PhoneAction
import com.aios.phone.model.PhoneAccountUiState
import com.aios.phone.model.PhoneUiState
import com.aios.phone.model.RiskUiState
import com.aios.phone.model.ThemePreference
import com.aios.phone.model.TranscriptUiState
import com.aios.phone.ui.AiosPhoneTheme
import com.aios.phone.ui.InCallScreen
import com.aios.phone.ui.PhoneHomeScreen
import com.aios.phone.ui.SettingsScreen

/** Visual-only harness. It declares no call permissions and cannot become a dialer. */
class PreviewActivity : ComponentActivity() {
    private companion object {
        const val PREFS = "preview_ui"
        const val SHOW_DIALER_ROLE_PROMPT = "show_dialer_role_prompt"
    }

    private enum class Screen { HOME, CALL, SETTINGS }

    private var screen by mutableStateOf(Screen.HOME)
    private var state by mutableStateOf(mockState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = state.copy(
            showDialerRolePrompt = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(SHOW_DIALER_ROLE_PROMPT, true),
        )
        setContent {
            AiosPhoneTheme(state.themePreference) {
                when (screen) {
                    Screen.HOME -> PhoneHomeScreen(
                        state = state,
                        dispatch = ::dispatch,
                        requestRole = { state = state.copy(message = "Disabled in the safe preview") },
                        openSettings = { screen = Screen.SETTINGS },
                        openCall = { screen = Screen.CALL },
                    )
                    Screen.CALL -> InCallScreen(
                        state = state,
                        dispatch = ::dispatch,
                        requestCameraAction = ::dispatch,
                    ) { screen = Screen.HOME }
                    Screen.SETTINGS -> SettingsScreen(state, ::dispatch) { screen = Screen.HOME }
                }
            }
        }
    }

    private fun dispatch(action: PhoneAction) {
        state = when (action) {
            is PhoneAction.ChangeDialInput -> state.copy(dialInput = action.value)
            is PhoneAction.ChangeDialWithRtt -> state.copy(dialWithRtt = action.enabled)
            is PhoneAction.ChangeHomeSection -> state.copy(homeSection = action.section)
            PhoneAction.PlaceCall -> state.copy(message = "Calling is disabled in this preview")
            is PhoneAction.DialNumber -> state.copy(message = "Calling is disabled in this preview")
            PhoneAction.ReloadRecentCalls -> state.copy(recentCallsLoading = false)
            PhoneAction.ReloadVoicemails -> state.copy(voicemailsLoading = false)
            is PhoneAction.ToggleVoicemail -> {
                val playing = state.voicemailPlaybackId == action.voicemailId &&
                    state.voicemailPlaybackState ==
                    com.aios.phone.model.VoicemailPlaybackState.PLAYING
                state.copy(
                    voicemailPlaybackId = action.voicemailId,
                    voicemailPlaybackState = if (playing) {
                        com.aios.phone.model.VoicemailPlaybackState.PAUSED
                    } else {
                        com.aios.phone.model.VoicemailPlaybackState.PLAYING
                    },
                )
            }
            is PhoneAction.FetchVoicemail -> state.copy(
                message = "Voicemail download requested in preview",
            )
            is PhoneAction.SelectCall -> state.copy(selectedCallId = action.callId)
            is PhoneAction.Answer -> updateCall(action.callId) {
                it.copy(state = Call.STATE_ACTIVE, videoState = action.videoState)
            }
            is PhoneAction.Ignore -> state.copy(message = "Call silenced; it is still ringing")
            is PhoneAction.AnswerWithAi -> updateCall(action.callId) {
                it.copy(state = Call.STATE_ACTIVE)
            }.copy(message = "AI receptionist answered in preview")
            is PhoneAction.Reject -> state.copy(calls = state.calls.filterNot { it.id == action.callId })
            is PhoneAction.Disconnect -> state.copy(calls = state.calls.filterNot { it.id == action.callId })
            is PhoneAction.Hold -> updateCall(action.callId) { it.copy(state = Call.STATE_HOLDING) }
            is PhoneAction.Unhold -> updateCall(action.callId) { it.copy(state = Call.STATE_ACTIVE) }
            is PhoneAction.SetMuted -> state.copy(muted = action.muted)
            is PhoneAction.SelectEndpoint -> state.copy(currentEndpointId = action.endpointId)
            is PhoneAction.SelectPhoneAccount -> state.copy(message = "SIM selected in preview")
            is PhoneAction.ContinuePostDial -> state.copy(message = "Post-dial choice previewed")
            is PhoneAction.RequestRtt -> updateCall(action.callId) { it.copy(rttActive = true) }
            is PhoneAction.RespondToRtt -> updateCall(action.callId) {
                it.copy(rttActive = action.accept, hasPendingRttRequest = false)
            }
            is PhoneAction.ChangeRttText -> state.copy(
                rttConversations = state.rttConversations + (
                    action.callId to (state.rttConversations[action.callId]
                        ?: com.aios.phone.model.RttUiState()).copy(localText = action.value)
                ),
            )
            is PhoneAction.StopRtt -> updateCall(action.callId) { it.copy(rttActive = false) }
            is PhoneAction.RequestVideoState -> updateCall(action.callId) {
                it.copy(videoState = action.videoState)
            }
            is PhoneAction.RespondToVideo -> updateCall(action.callId) {
                it.copy(
                    videoState = if (action.accept) {
                        it.pendingVideoState ?: it.videoState
                    } else {
                        it.videoState
                    },
                    pendingVideoState = null,
                )
            }
            is PhoneAction.AttachVideoDisplay,
            is PhoneAction.AttachVideoPreview,
            is PhoneAction.SetVideoOrientation -> state
            PhoneAction.ToggleDtmf -> state.copy(showDtmf = !state.showDtmf)
            is PhoneAction.ChangeTheme -> state.copy(themePreference = action.preference)
            is PhoneAction.ChangeDialerRolePromptVisible -> {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(SHOW_DIALER_ROLE_PROMPT, action.visible)
                    .apply()
                state.copy(showDialerRolePrompt = action.visible)
            }
            is PhoneAction.ChangeProcessingEnabled -> state.copy(
                assistantPolicy = state.assistantPolicy.copy(processingEnabled = action.enabled),
            )
            is PhoneAction.ChangeAutoAnswerEnabled -> state.copy(
                assistantPolicy = state.assistantPolicy.copy(
                    answerMode = if (action.enabled) {
                        state.assistantPolicy.answerMode.takeUnless { it == "off" }
                            ?: "unknown_only"
                    } else {
                        "off"
                    },
                ),
            )
            is PhoneAction.ChangeAnswerMode -> state.copy(
                assistantPolicy = state.assistantPolicy.copy(answerMode = action.mode),
            )
            is PhoneAction.ChangeAnswerDelayMode -> state.copy(
                assistantPolicy = state.assistantPolicy.copy(answerDelayMode = action.mode),
            )
            is PhoneAction.ChangeMissedDelay -> state.copy(
                assistantPolicy = state.assistantPolicy.copy(missedDelayMillis = action.millis),
            )
            PhoneAction.SaveAssistantPolicy -> state.copy(message = "Settings saved in preview")
            PhoneAction.ReloadAssistantPolicy -> state
            PhoneAction.ClearMessage -> state.copy(message = null)
            is PhoneAction.Merge -> state.copy(message = "Conference action previewed")
            is PhoneAction.Split -> state.copy(message = "Separate action previewed")
            is PhoneAction.SwapConference -> state.copy(message = "Swap action previewed")
            is PhoneAction.SendDtmf, is PhoneAction.StopDtmf -> state
        }
    }

    private fun updateCall(id: String, block: (CallUiState) -> CallUiState): PhoneUiState =
        state.copy(calls = state.calls.map { if (it.id == id) block(it) else it })

    private fun mockState(): PhoneUiState {
        val primary = CallUiState(
            id = "preview-primary",
            displayName = "Martinez Plumbing",
            address = "••• ••• 0182",
            state = Call.STATE_ACTIVE,
            direction = Call.Details.DIRECTION_INCOMING,
            capabilities = Call.Details.CAPABILITY_HOLD or
                Call.Details.CAPABILITY_MERGE_CONFERENCE or
                Call.Details.CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL or
                Call.Details.CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL or
                Call.Details.CAPABILITY_CAN_PAUSE_VIDEO,
            properties = 0,
            videoState = 0,
            connectTimeMillis = System.currentTimeMillis() - 74_000,
            parentId = null,
            childIds = emptyList(),
            conferenceableIds = listOf("preview-second"),
            canRequestRtt = true,
        )
        val second = CallUiState(
            id = "preview-second",
            displayName = "New customer",
            address = "••• ••• 7741",
            state = Call.STATE_HOLDING,
            direction = Call.Details.DIRECTION_INCOMING,
            capabilities = Call.Details.CAPABILITY_HOLD,
            properties = 0,
            videoState = 0,
            connectTimeMillis = 0,
            parentId = null,
            childIds = emptyList(),
            conferenceableIds = listOf("preview-primary"),
        )
        return PhoneUiState(
            calls = listOf(primary, second),
            selectedCallId = primary.id,
            endpoints = listOf(
                AudioEndpointUiState("earpiece", "Phone", CallEndpoint.TYPE_EARPIECE),
                AudioEndpointUiState("speaker", "Speaker", CallEndpoint.TYPE_SPEAKER),
                AudioEndpointUiState("bluetooth", "Pixel Buds", CallEndpoint.TYPE_BLUETOOTH),
            ),
            phoneAccounts = listOf(
                PhoneAccountUiState("sim-1", "Personal SIM"),
                PhoneAccountUiState("sim-2", "Business eSIM"),
            ),
            currentEndpointId = "earpiece",
            muted = false,
            dialInput = "",
            homeSection = HomeSection.DIAL,
            recentCalls = listOf(
                com.aios.phone.model.RecentCallUiState(
                    id = "recent-1",
                    displayName = "Martinez Plumbing",
                    number = "+1 555 010 0182",
                    type = android.provider.CallLog.Calls.INCOMING_TYPE,
                    timestampMillis = System.currentTimeMillis() - 12 * 60_000L,
                    durationSeconds = 246,
                ),
                com.aios.phone.model.RecentCallUiState(
                    id = "recent-2",
                    displayName = "Potential spam",
                    number = "+1 555 010 9921",
                    type = android.provider.CallLog.Calls.MISSED_TYPE,
                    timestampMillis = System.currentTimeMillis() - 95 * 60_000L,
                    durationSeconds = 0,
                ),
            ),
            voicemails = listOf(
                com.aios.phone.model.VoicemailUiState(
                    id = "voicemail-1",
                    number = "+1 555 010 0182",
                    timestampMillis = System.currentTimeMillis() - 28 * 60_000L,
                    durationSeconds = 38,
                    isRead = false,
                    hasContent = true,
                    mimeType = "audio/amr",
                    transcription = "Hi, this is Carlos. The water heater is leaking again. Please call me back.",
                ),
                com.aios.phone.model.VoicemailUiState(
                    id = "voicemail-2",
                    number = "+1 555 010 7741",
                    timestampMillis = System.currentTimeMillis() - 4 * 60 * 60_000L,
                    durationSeconds = 21,
                    isRead = true,
                    hasContent = false,
                    mimeType = "audio/amr",
                    transcription = "",
                ),
            ),
            isDialerRoleHeld = false,
            telecomConnected = false,
            assistantConnected = true,
            assistantPolicy = AssistantPolicyUiState(
                available = true,
                processingEnabled = true,
                answerMode = "unknown_only",
                answerDelayMode = "fixed_2000_ms",
                missedDelayMillis = 15_000L,
                automaticAnswerAvailable = false,
                automaticAnswerUnavailableReason = "caller_audio_injection_not_implemented",
            ),
            themePreference = ThemePreference.SYSTEM,
            transcripts = mapOf(
                primary.id to listOf(
                    TranscriptUiState("downlink", "en", "Hi, I need someone to look at a leaking water heater.", true, 0),
                    TranscriptUiState("uplink", "en", "I can help schedule that. What address should we use?", true, 3200),
                    TranscriptUiState("downlink", "en", "It's the shop on Franklin Street.", true, 6900),
                ),
            ),
            risks = mapOf(primary.id to RiskUiState(8, "Likely legitimate • service request")),
        )
    }
}
