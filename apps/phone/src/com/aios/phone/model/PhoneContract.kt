package com.aios.phone.model

import android.telecom.Call
import android.telecom.CallEndpoint
import android.telecom.VideoProfile
import android.view.Surface

/** Immutable state consumed by Compose. It contains no mutable Telecom objects. */
data class PhoneUiState(
    val calls: List<CallUiState> = emptyList(),
    val selectedCallId: String? = null,
    val endpoints: List<AudioEndpointUiState> = emptyList(),
    val phoneAccounts: List<PhoneAccountUiState> = emptyList(),
    val currentEndpointId: String? = null,
    val muted: Boolean = false,
    val showDtmf: Boolean = false,
    val dialInput: String = "",
    val dialWithRtt: Boolean = false,
    val homeSection: HomeSection = HomeSection.DIAL,
    val recentCalls: List<RecentCallUiState> = emptyList(),
    val recentCallsLoading: Boolean = false,
    val recentCallsError: String? = null,
    val voicemails: List<VoicemailUiState> = emptyList(),
    val voicemailsLoading: Boolean = false,
    val voicemailsError: String? = null,
    val voicemailPlaybackId: String? = null,
    val voicemailPlaybackState: VoicemailPlaybackState = VoicemailPlaybackState.STOPPED,
    val isDialerRoleHeld: Boolean = false,
    val showDialerRolePrompt: Boolean = true,
    val telecomConnected: Boolean = false,
    val assistantConnected: Boolean = false,
    val assistantPolicy: AssistantPolicyUiState = AssistantPolicyUiState(),
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val transcripts: Map<String, List<TranscriptUiState>> = emptyMap(),
    val risks: Map<String, RiskUiState> = emptyMap(),
    val assistantCalls: Map<String, AssistantCallUiState> = emptyMap(),
    val rttConversations: Map<String, RttUiState> = emptyMap(),
    val message: String? = null,
) {
    val selectedCall: CallUiState?
        get() = calls.firstOrNull { it.id == selectedCallId } ?: calls.firstOrNull()
}

data class AssistantPolicyUiState(
    val available: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val processingEnabled: Boolean = false,
    val callerHistoryEnabled: Boolean = false,
    val messageHistoryEnabled: Boolean = true,
    val callHistoryEnabled: Boolean = true,
    val photoHistoryEnabled: Boolean = true,
    val excludedCallerHistoryAddressHashes: Set<String> = emptySet(),
    val answerMode: String = "off",
    val answerDelayMode: String = "fixed_2000_ms",
    val missedDelayMillis: Long = 15_000L,
    val automaticAnswerAvailable: Boolean = false,
    val automaticAnswerUnavailableReason: String = "service_unavailable",
    val error: String? = null,
) {
    val autoAnswerEnabled: Boolean get() = answerMode != "off"
    val hasEnabledCallerHistorySource: Boolean
        get() = messageHistoryEnabled || callHistoryEnabled || photoHistoryEnabled

    fun withCallerHistoryEnabled(enabled: Boolean): AssistantPolicyUiState =
        if (enabled && !hasEnabledCallerHistorySource) {
            copy(
                callerHistoryEnabled = true,
                messageHistoryEnabled = true,
                callHistoryEnabled = true,
                photoHistoryEnabled = true,
            )
        } else {
            copy(callerHistoryEnabled = enabled)
        }

    fun withoutEmptyCallerHistory(): AssistantPolicyUiState =
        if (callerHistoryEnabled && !hasEnabledCallerHistorySource) {
            copy(callerHistoryEnabled = false)
        } else {
            this
        }
}

enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK,
}

enum class HomeSection {
    DIAL,
    RECENTS,
    VOICEMAIL,
}

data class RecentCallUiState(
    val id: String,
    val displayName: String,
    val number: String,
    val type: Int,
    val timestampMillis: Long,
    val durationSeconds: Long,
    val callerHistoryExcluded: Boolean = false,
)

data class VoicemailUiState(
    val id: String,
    val number: String,
    val timestampMillis: Long,
    val durationSeconds: Long,
    val isRead: Boolean,
    val hasContent: Boolean,
    val mimeType: String,
    val transcription: String,
)

enum class VoicemailPlaybackState {
    STOPPED,
    PREPARING,
    PLAYING,
    PAUSED,
}

data class CallUiState(
    val id: String,
    val displayName: String,
    val address: String,
    val state: Int,
    val direction: Int,
    val capabilities: Int,
    val properties: Int,
    val videoState: Int,
    val connectTimeMillis: Long,
    val parentId: String?,
    val childIds: List<String>,
    val conferenceableIds: List<String>,
    val hasPostDialWait: Boolean = false,
    val silentRingingRequested: Boolean = false,
    val canRequestRtt: Boolean = false,
    val rttActive: Boolean = false,
    val rttMode: Int = 0,
    val hasPendingRttRequest: Boolean = false,
    val pendingVideoState: Int? = null,
    val videoPeerWidth: Int = 0,
    val videoPeerHeight: Int = 0,
    val videoQuality: Int = 0,
) {
    val isRinging: Boolean get() = state == Call.STATE_RINGING
    val isActive: Boolean get() = state == Call.STATE_ACTIVE
    val isOnHold: Boolean get() = state == Call.STATE_HOLDING
    val canHold: Boolean get() = hasCapability(Call.Details.CAPABILITY_HOLD)
    val canMerge: Boolean get() = hasCapability(Call.Details.CAPABILITY_MERGE_CONFERENCE)
    val canSwap: Boolean get() = hasCapability(Call.Details.CAPABILITY_SWAP_CONFERENCE)
    val canSeparate: Boolean get() = hasCapability(Call.Details.CAPABILITY_SEPARATE_FROM_CONFERENCE)
    val isVideo: Boolean get() = VideoProfile.isVideo(videoState)
    val sendsVideo: Boolean get() = VideoProfile.isTransmissionEnabled(videoState)
    val receivesVideo: Boolean get() = VideoProfile.isReceptionEnabled(videoState)
    val canStartVideo: Boolean get() =
        hasCapability(Call.Details.CAPABILITY_SUPPORTS_VT_LOCAL_BIDIRECTIONAL) &&
            hasCapability(Call.Details.CAPABILITY_SUPPORTS_VT_REMOTE_BIDIRECTIONAL)
    val canPauseVideo: Boolean get() =
        isVideo && hasCapability(Call.Details.CAPABILITY_CAN_PAUSE_VIDEO)
    val canDowngradeVideo: Boolean get() =
        isVideo && !hasCapability(Call.Details.CAPABILITY_CANNOT_DOWNGRADE_VIDEO_TO_AUDIO)

    fun hasCapability(capability: Int): Boolean = capabilities and capability == capability
}

data class AudioEndpointUiState(
    val id: String,
    val label: String,
    val type: Int,
) {
    val isBluetooth: Boolean get() = type == CallEndpoint.TYPE_BLUETOOTH
}

data class PhoneAccountUiState(
    val id: String,
    val label: String,
)

data class RiskUiState(
    val score: Int,
    val label: CallRiskLabel,
    val reasonCode: String,
    val source: CallRiskSource,
    val revision: Long,
    val observedAtEpochMillis: Long,
)

data class AssistantCallUiState(
    val aiHandling: Boolean,
    val revision: Long,
    val observedAtEpochMillis: Long,
)

data class RttUiState(
    val localText: String = "",
    val remoteText: String = "",
    val error: String? = null,
)

sealed interface PhoneAction {
    data class ChangeDialInput(val value: String) : PhoneAction
    data class ChangeDialWithRtt(val enabled: Boolean) : PhoneAction
    data class ChangeHomeSection(val section: HomeSection) : PhoneAction
    data object PlaceCall : PhoneAction
    data class DialNumber(val number: String) : PhoneAction
    data class MessageNumber(val number: String) : PhoneAction
    data object ReloadRecentCalls : PhoneAction
    data object ReloadVoicemails : PhoneAction
    data class ToggleVoicemail(val voicemailId: String) : PhoneAction
    data class FetchVoicemail(val voicemailId: String) : PhoneAction
    data class SelectCall(val callId: String) : PhoneAction
    data class Answer(
        val callId: String,
        val videoState: Int = VideoProfile.STATE_AUDIO_ONLY,
    ) : PhoneAction
    data class ClaimOwnerAnswer(val callId: String) : PhoneAction
    data class Ignore(val callId: String) : PhoneAction
    data class AnswerWithAi(val callId: String) : PhoneAction
    data class TakeOver(val callId: String) : PhoneAction
    data class Reject(val callId: String) : PhoneAction
    data class Disconnect(val callId: String) : PhoneAction
    data class Hold(val callId: String) : PhoneAction
    data class Unhold(val callId: String) : PhoneAction
    data class SendDtmf(val callId: String, val digit: Char) : PhoneAction
    data class StopDtmf(val callId: String) : PhoneAction
    data class Merge(val callId: String, val otherCallId: String) : PhoneAction
    data class Split(val callId: String) : PhoneAction
    data class SwapConference(val callId: String) : PhoneAction
    data class SetMuted(val muted: Boolean) : PhoneAction
    data class SelectEndpoint(val endpointId: String) : PhoneAction
    data class SelectPhoneAccount(val callId: String, val accountId: String) : PhoneAction
    data class ContinuePostDial(val callId: String, val proceed: Boolean) : PhoneAction
    data class RequestRtt(val callId: String) : PhoneAction
    data class RespondToRtt(val callId: String, val accept: Boolean) : PhoneAction
    data class ChangeRttText(val callId: String, val value: String) : PhoneAction
    data class StopRtt(val callId: String) : PhoneAction
    data class RequestVideoState(val callId: String, val videoState: Int) : PhoneAction
    data class RespondToVideo(val callId: String, val accept: Boolean) : PhoneAction
    data class AttachVideoDisplay(val callId: String, val surface: Surface?) : PhoneAction
    data class AttachVideoPreview(val callId: String, val surface: Surface?) : PhoneAction
    data class SetVideoOrientation(val callId: String, val degrees: Int) : PhoneAction
    data object ToggleDtmf : PhoneAction
    data class ChangeTheme(val preference: ThemePreference) : PhoneAction
    data class ChangeDialerRolePromptVisible(val visible: Boolean) : PhoneAction
    data class ChangeProcessingEnabled(val enabled: Boolean) : PhoneAction
    data class ChangeCallerHistoryEnabled(val enabled: Boolean) : PhoneAction
    data class ChangeMessageHistoryEnabled(val enabled: Boolean) : PhoneAction
    data class ChangeCallHistoryEnabled(val enabled: Boolean) : PhoneAction
    data class ChangePhotoHistoryEnabled(val enabled: Boolean) : PhoneAction
    data class ChangeConversationHistory(val number: String, val enabled: Boolean) : PhoneAction
    data class ChangeAutoAnswerEnabled(val enabled: Boolean) : PhoneAction
    data class ChangeAnswerMode(val mode: String) : PhoneAction
    data class ChangeAnswerDelayMode(val mode: String) : PhoneAction
    data class ChangeMissedDelay(val millis: Long) : PhoneAction
    data object SaveAssistantPolicy : PhoneAction
    data object ReloadAssistantPolicy : PhoneAction
    data object ClearMessage : PhoneAction
}
