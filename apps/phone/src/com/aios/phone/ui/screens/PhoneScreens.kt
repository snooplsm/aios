package com.aios.phone.ui

import android.telecom.Call
import android.provider.CallLog
import android.text.format.DateUtils
import android.telecom.VideoProfile
import android.view.Surface as ViewSurface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aios.phone.model.CallRiskLabel
import com.aios.phone.model.CallRiskSemantics
import com.aios.phone.model.CallUiState
import com.aios.phone.model.AssistantPolicySemantics
import com.aios.phone.model.HomeSection
import com.aios.phone.model.PhoneAction
import com.aios.phone.model.PhoneUiState
import com.aios.phone.model.ThemePreference
import com.aios.phone.model.VoicemailPlaybackState

@Composable
fun PhoneHomeScreen(
    state: PhoneUiState,
    dispatch: (PhoneAction) -> Unit,
    requestRole: () -> Unit,
    openSettings: () -> Unit,
    openCall: () -> Unit,
) {
    Scaffold { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("AIOS Phone", style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text("Private, on-device call assistance",
                        style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(onClick = openSettings) { Text("Settings") }
            }

            if (!state.isDialerRoleHeld && state.showDialerRolePrompt) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text("Preview mode", fontWeight = FontWeight.SemiBold)
                            TextButton(
                                onClick = {
                                    dispatch(PhoneAction.ChangeDialerRolePromptVisible(false))
                                },
                            ) { Text("Dismiss") }
                        }
                        Text("AIOS Phone is preloaded as the default on AIOS builds. Restore it here if you selected another calling app.")
                        Button(onClick = requestRole) { Text("Restore AIOS Phone") }
                    }
                }
            }

            state.message?.let { message ->
                Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(message, modifier = Modifier.weight(1f))
                        TextButton(onClick = { dispatch(PhoneAction.ClearMessage) }) { Text("Dismiss") }
                    }
                }
            }

            if (state.calls.isNotEmpty()) {
                Card(onClick = openCall, modifier = Modifier.fillMaxWidth()) {
                    val current = state.selectedCall ?: state.calls.first()
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${state.calls.size} call${if (state.calls.size == 1) "" else "s"} in progress",
                            color = MaterialTheme.colorScheme.primary)
                        Text(current.displayName, style = MaterialTheme.typography.titleLarge)
                        Text(callStateLabel(current.state))
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeSection.entries.forEach { section ->
                    val selected = state.homeSection == section
                    val label = when (section) {
                        HomeSection.DIAL -> "Dial"
                        HomeSection.RECENTS -> "Recents"
                        HomeSection.VOICEMAIL -> "Voicemail"
                    }
                    if (selected) {
                        Button(
                            onClick = { dispatch(PhoneAction.ChangeHomeSection(section)) },
                            modifier = Modifier.weight(1f),
                        ) { Text(label) }
                    } else {
                        OutlinedButton(
                            onClick = { dispatch(PhoneAction.ChangeHomeSection(section)) },
                            modifier = Modifier.weight(1f),
                        ) { Text(label) }
                    }
                }
            }

            if (state.homeSection == HomeSection.DIAL) {
                OutlinedTextField(
                    value = state.dialInput,
                    onValueChange = { dispatch(PhoneAction.ChangeDialInput(it)) },
                    label = { Text("Phone number") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                DialPad(
                    onDigit = { dispatch(PhoneAction.ChangeDialInput(state.dialInput + it)) },
                    onLongDigit = {},
                )
                Button(
                    onClick = { dispatch(PhoneAction.PlaceCall) },
                    enabled = state.dialInput.isNotBlank() && state.isDialerRoleHeld,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("Call") }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Start with RTT")
                        Text("Real-time text, when supported by the carrier",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = state.dialWithRtt,
                        onCheckedChange = { dispatch(PhoneAction.ChangeDialWithRtt(it)) },
                    )
                }
            } else if (state.homeSection == HomeSection.RECENTS) {
                RecentCalls(state, dispatch)
            } else {
                Voicemails(state, dispatch)
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Assistant status", fontWeight = FontWeight.SemiBold)
                    Text("Incoming speech: real-time English and Spanish")
                    Text("Processing: on device • retention: 24 hours")
                    when {
                        !state.assistantPolicy.available -> Text(
                            "Automatic answering: assistant service unavailable",
                            color = MaterialTheme.colorScheme.error,
                        )
                        state.assistantPolicy.automaticAnswerAvailable -> Text(
                            "Automatic answering: ready",
                            color = MaterialTheme.colorScheme.primary,
                        )
                        else -> Text(
                            "Automatic answering: locked until caller audio passes physical validation",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Voicemails(state: PhoneUiState, dispatch: (PhoneAction) -> Unit) {
    when {
        state.voicemailsLoading && state.voicemails.isEmpty() -> Text("Loading voicemail…")
        state.voicemailsError != null -> {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.voicemailsError, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(
                        onClick = { dispatch(PhoneAction.ReloadVoicemails) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Try again") }
                }
            }
        }
        state.voicemails.isEmpty() -> Text("No voicemail")
        else -> state.voicemails.forEach { voicemail ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(voicemail.number, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f))
                        if (!voicemail.isRead) {
                            Text("New", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    val relative = DateUtils.getRelativeTimeSpanString(
                        voicemail.timestampMillis,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    )
                    val minutes = voicemail.durationSeconds / 60
                    val seconds = voicemail.durationSeconds % 60
                    Text("$relative • $minutes:${seconds.toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.bodySmall)
                    if (voicemail.transcription.isNotBlank()) {
                        Text(voicemail.transcription)
                    }
                    if (voicemail.hasContent) {
                        val active = state.voicemailPlaybackId == voicemail.id
                        val playback = if (active) {
                            state.voicemailPlaybackState
                        } else {
                            VoicemailPlaybackState.STOPPED
                        }
                        Button(
                            onClick = { dispatch(PhoneAction.ToggleVoicemail(voicemail.id)) },
                            enabled = playback != VoicemailPlaybackState.PREPARING,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(when (playback) {
                                VoicemailPlaybackState.PREPARING -> "Loading…"
                                VoicemailPlaybackState.PLAYING -> "Pause"
                                VoicemailPlaybackState.PAUSED -> "Resume"
                                VoicemailPlaybackState.STOPPED -> "Play"
                            })
                        }
                    } else {
                        OutlinedButton(
                            onClick = { dispatch(PhoneAction.FetchVoicemail(voicemail.id)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Download audio") }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentCalls(state: PhoneUiState, dispatch: (PhoneAction) -> Unit) {
    when {
        state.recentCallsLoading && state.recentCalls.isEmpty() -> Text("Loading recent calls…")
        state.recentCallsError != null -> {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.recentCallsError, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(
                        onClick = { dispatch(PhoneAction.ReloadRecentCalls) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Try again") }
                }
            }
        }
        state.recentCalls.isEmpty() -> Text("No recent calls")
        else -> state.recentCalls.forEach { recent ->
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(recent.displayName, fontWeight = FontWeight.SemiBold)
                        if (recent.number.isNotBlank() && recent.number != recent.displayName) {
                            Text(recent.number, style = MaterialTheme.typography.bodySmall)
                        }
                        val direction = when (recent.type) {
                            CallLog.Calls.INCOMING_TYPE -> "Incoming"
                            CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                            CallLog.Calls.MISSED_TYPE -> "Missed"
                            CallLog.Calls.REJECTED_TYPE -> "Declined"
                            CallLog.Calls.BLOCKED_TYPE -> "Blocked"
                            CallLog.Calls.VOICEMAIL_TYPE -> "Voicemail"
                            else -> "Call"
                        }
                        val relative = DateUtils.getRelativeTimeSpanString(
                            recent.timestampMillis,
                            System.currentTimeMillis(),
                            DateUtils.MINUTE_IN_MILLIS,
                        )
                        Text("$direction • $relative", style = MaterialTheme.typography.bodySmall)
                    }
                    if (recent.number.isNotBlank()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Button(
                                onClick = { dispatch(PhoneAction.DialNumber(recent.number)) },
                                enabled = state.isDialerRoleHeld,
                            ) { Text("Call") }
                            OutlinedButton(
                                onClick = { dispatch(PhoneAction.MessageNumber(recent.number)) },
                            ) { Text("Message") }
                            OutlinedButton(
                                onClick = {
                                    dispatch(PhoneAction.ChangeConversationHistory(
                                        recent.number,
                                        enabled = recent.callerHistoryExcluded,
                                    ))
                                },
                                enabled = state.assistantPolicy.available
                                    && !state.assistantPolicy.saving,
                            ) {
                                Text(if (recent.callerHistoryExcluded) {
                                    "Allow AI history"
                                } else {
                                    "Exclude AI history"
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InCallScreen(
    state: PhoneUiState,
    dispatch: (PhoneAction) -> Unit,
    requestCameraAction: (PhoneAction) -> Unit,
    close: () -> Unit,
) {
    val selected = state.selectedCall
    val callScrollState = rememberScrollState()
    LaunchedEffect(selected?.id, selected?.isRinging) {
        // A waiting call must put its owner controls on screen immediately,
        // even when the previous active-call surface was scrolled downward.
        callScrollState.scrollTo(0)
    }
    Scaffold { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets).verticalScroll(callScrollState)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.fillMaxWidth()) {
                Text(
                    "AIOS Phone",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            if (selected == null) {
                Text("No call in progress", style = MaterialTheme.typography.headlineSmall)
                Button(onClick = close) { Text("Close") }
                return@Column
            }
            val aiHandling = state.assistantCalls[selected.id]?.aiHandling == true

            Text(callStateLabel(selected.state), color = MaterialTheme.colorScheme.primary)
            Text(selected.displayName, style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold)
            if (selected.address != selected.displayName) Text(selected.address)

            if (selected.isVideo) {
                VideoCallSurfaces(selected, dispatch)
            }

            if (aiHandling) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("AI receptionist is handling this call",
                                fontWeight = FontWeight.SemiBold)
                            Text(
                                "Take over to stop AI speech. Live transcription stays on.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(onClick = { dispatch(PhoneAction.TakeOver(selected.id)) }) {
                            Text("Take over")
                        }
                    }
                }
            }

            state.risks[selected.id]?.let { risk ->
                val containerColor = when (risk.label) {
                    CallRiskLabel.LIKELY_LEGITIMATE -> MaterialTheme.colorScheme.primaryContainer
                    CallRiskLabel.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant
                    CallRiskLabel.SUSPICIOUS -> MaterialTheme.colorScheme.tertiaryContainer
                    CallRiskLabel.HIGH_RISK -> MaterialTheme.colorScheme.errorContainer
                }
                val contentColor = when (risk.label) {
                    CallRiskLabel.LIKELY_LEGITIMATE -> MaterialTheme.colorScheme.onPrimaryContainer
                    CallRiskLabel.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                    CallRiskLabel.SUSPICIOUS -> MaterialTheme.colorScheme.onTertiaryContainer
                    CallRiskLabel.HIGH_RISK -> MaterialTheme.colorScheme.onErrorContainer
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = containerColor,
                        contentColor = contentColor,
                    ),
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(risk.label.headline, fontWeight = FontWeight.SemiBold)
                        Text(CallRiskSemantics.explanation(risk.label, risk.reasonCode))
                        Text(
                            "Risk score ${risk.score}/100 · ${risk.source.displayName}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (selected.isRinging) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { dispatch(PhoneAction.Reject(selected.id)) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Decline") }
                    OutlinedButton(
                        onClick = {
                            dispatch(PhoneAction.Ignore(selected.id))
                            close()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Ignore") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { dispatch(PhoneAction.Answer(selected.id)) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Answer") }
                    Button(
                        onClick = { dispatch(PhoneAction.AnswerWithAi(selected.id)) },
                        enabled = state.assistantPolicy.automaticAnswerAvailable
                                && state.assistantPolicy.processingEnabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                        ),
                        modifier = Modifier.weight(1f),
                    ) { Text("AI") }
                }
                if (!state.assistantPolicy.automaticAnswerAvailable) {
                    Text(
                        "AI answering unlocks after caller-audio output passes device validation.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (selected.isVideo) {
                    Button(
                        onClick = {
                            requestCameraAction(
                                PhoneAction.Answer(selected.id, selected.videoState),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Answer video") }
                }
            } else if (selected.state == Call.STATE_SELECT_PHONE_ACCOUNT) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Choose a SIM", style = MaterialTheme.typography.titleMedium)
                        if (state.phoneAccounts.isEmpty()) {
                            Text("No calling account is currently available.")
                        }
                        state.phoneAccounts.forEach { account ->
                            Button(
                                onClick = {
                                    dispatch(PhoneAction.SelectPhoneAccount(selected.id, account.id))
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(account.label) }
                        }
                        OutlinedButton(
                            onClick = { dispatch(PhoneAction.Disconnect(selected.id)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Cancel call") }
                    }
                }
            } else {
                CallControls(state, selected, dispatch)
                Button(
                    onClick = { dispatch(PhoneAction.Disconnect(selected.id)) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("End call") }
            }

            if (selected.hasPendingRttRequest) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("RTT request", style = MaterialTheme.typography.titleMedium)
                        Text("The other caller wants to start real-time text.")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    dispatch(PhoneAction.RespondToRtt(selected.id, false))
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Decline") }
                            Button(
                                onClick = {
                                    dispatch(PhoneAction.RespondToRtt(selected.id, true))
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Accept") }
                        }
                    }
                }
            }

            selected.pendingVideoState?.let { requestedVideoState ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Video request", style = MaterialTheme.typography.titleMedium)
                        Text("The other caller wants to change this to a video call.")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    dispatch(PhoneAction.RespondToVideo(selected.id, false))
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Decline") }
                            Button(
                                onClick = {
                                    val action = PhoneAction.RespondToVideo(selected.id, true)
                                    if (VideoProfile.isTransmissionEnabled(requestedVideoState)) {
                                        requestCameraAction(action)
                                    } else {
                                        dispatch(action)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Accept") }
                        }
                    }
                }
            }

            if (selected.state != Call.STATE_SELECT_PHONE_ACCOUNT) {
                val transcript = state.transcripts[selected.id].orEmpty()
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Live transcript", style = MaterialTheme.typography.titleMedium)
                        if (transcript.isEmpty()) {
                            Text(
                                if (selected.isRinging) {
                                    "Transcription starts after answer."
                                } else {
                                    "Listening on device…"
                                },
                            )
                        }
                        transcript.takeLast(6).forEach { line ->
                            val speaker = when {
                                line.direction == "downlink" -> "Caller"
                                aiHandling -> "AI"
                                else -> "You"
                            }
                            Text("$speaker: ${line.text}")
                        }
                    }
                }
            }

            if (selected.rttActive) {
                val rtt = state.rttConversations[selected.id]
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Real-time text", style = MaterialTheme.typography.titleMedium)
                        Text(
                            rtt?.remoteText?.ifBlank { "Waiting for the other caller…" }
                                ?: "Waiting for the other caller…",
                        )
                        OutlinedTextField(
                            value = rtt?.localText.orEmpty(),
                            onValueChange = {
                                dispatch(PhoneAction.ChangeRttText(selected.id, it))
                            },
                            label = { Text("Text as you type") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        rtt?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        OutlinedButton(
                            onClick = { dispatch(PhoneAction.StopRtt(selected.id)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Stop RTT") }
                    }
                }
            }

            if (selected.hasPostDialWait) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Continue dialing?", style = MaterialTheme.typography.titleMedium)
                        Text("The number contains additional digits waiting to be sent.")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    dispatch(PhoneAction.ContinuePostDial(selected.id, false))
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Cancel") }
                            Button(
                                onClick = {
                                    dispatch(PhoneAction.ContinuePostDial(selected.id, true))
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Continue") }
                        }
                    }
                }
            }

            if (!selected.isRinging && selected.state != Call.STATE_SELECT_PHONE_ACCOUNT) {
                SecondaryCallControls(state, selected, dispatch)
                VideoAndRttControls(selected, dispatch, requestCameraAction)
                OutlinedButton(
                    onClick = { dispatch(PhoneAction.ToggleDtmf) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.showDtmf) "Hide keypad" else "Keypad") }
                if (state.showDtmf) {
                    DialPad(
                        onDigit = { dispatch(PhoneAction.SendDtmf(selected.id, it)) },
                        onLongDigit = { dispatch(PhoneAction.StopDtmf(selected.id)) },
                    )
                }
            }

            if (state.calls.size > 1) {
                Text("Calls", modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.titleMedium)
                state.calls.forEach { call ->
                    OutlinedButton(
                        onClick = { dispatch(PhoneAction.SelectCall(call.id)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("${call.displayName} • ${callStateLabel(call.state)}") }
                }
            }
        }
    }
}

@Composable
private fun VideoAndRttControls(
    call: CallUiState,
    dispatch: (PhoneAction) -> Unit,
    requestCameraAction: (PhoneAction) -> Unit,
) {
    if (!call.rttActive && call.canRequestRtt && call.isActive) {
        OutlinedButton(
            onClick = { dispatch(PhoneAction.RequestRtt(call.id)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start RTT") }
    }
    if (!call.isVideo && call.canStartVideo && call.isActive) {
        OutlinedButton(
            onClick = {
                requestCameraAction(
                    PhoneAction.RequestVideoState(call.id, VideoProfile.STATE_BIDIRECTIONAL),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start video") }
    }
    if (call.isVideo) {
        if (call.canPauseVideo) {
            val paused = VideoProfile.isPaused(call.videoState)
            OutlinedButton(
                onClick = {
                    val desired = if (paused) {
                        call.videoState and VideoProfile.STATE_PAUSED.inv()
                    } else {
                        call.videoState or VideoProfile.STATE_PAUSED
                    }
                    val action = PhoneAction.RequestVideoState(call.id, desired)
                    if (!paused && call.sendsVideo) requestCameraAction(action) else dispatch(action)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (paused) "Resume video" else "Pause video") }
        }
        if (call.canDowngradeVideo) {
            OutlinedButton(
                onClick = {
                    dispatch(PhoneAction.RequestVideoState(
                        call.id,
                        VideoProfile.STATE_AUDIO_ONLY,
                    ))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Turn off video") }
        }
    }
}

@Composable
private fun VideoCallSurfaces(call: CallUiState, dispatch: (PhoneAction) -> Unit) {
    val view = LocalView.current
    val orientation = when (view.display?.rotation) {
        ViewSurface.ROTATION_90 -> 90
        ViewSurface.ROTATION_180 -> 180
        ViewSurface.ROTATION_270 -> 270
        else -> 0
    }
    LaunchedEffect(call.id, orientation) {
        dispatch(PhoneAction.SetVideoOrientation(call.id, orientation))
    }
    DisposableEffect(call.id) {
        onDispose {
            dispatch(PhoneAction.AttachVideoDisplay(call.id, null))
            dispatch(PhoneAction.AttachVideoPreview(call.id, null))
        }
    }
    Box(
        Modifier.fillMaxWidth().height(320.dp).background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (call.receivesVideo) {
            AndroidView(
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                dispatch(PhoneAction.AttachVideoDisplay(call.id, holder.surface))
                            }
                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int,
                            ) = Unit
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                dispatch(PhoneAction.AttachVideoDisplay(call.id, null))
                            }
                        })
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("Sending camera", color = Color.White)
        }
        if (call.sendsVideo) {
            AndroidView(
                factory = { context ->
                    SurfaceView(context).apply {
                        setZOrderMediaOverlay(true)
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                dispatch(PhoneAction.AttachVideoPreview(call.id, holder.surface))
                            }
                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int,
                            ) = Unit
                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                dispatch(PhoneAction.AttachVideoPreview(call.id, null))
                            }
                        })
                    }
                },
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(108.dp, 144.dp),
            )
        }
    }
}

@Composable
private fun CallControls(
    state: PhoneUiState,
    call: CallUiState,
    dispatch: (PhoneAction) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { dispatch(PhoneAction.SetMuted(!state.muted)) },
            modifier = Modifier.weight(1f),
        ) { Text(if (state.muted) "Unmute" else "Mute") }
        if (call.isOnHold) {
            OutlinedButton(onClick = { dispatch(PhoneAction.Unhold(call.id)) },
                modifier = Modifier.weight(1f)) { Text("Resume") }
        } else {
            OutlinedButton(onClick = { dispatch(PhoneAction.Hold(call.id)) },
                enabled = call.canHold, modifier = Modifier.weight(1f)) { Text("Hold") }
        }
    }
}

@Composable
private fun SecondaryCallControls(
    state: PhoneUiState,
    call: CallUiState,
    dispatch: (PhoneAction) -> Unit,
) {
    if (state.endpoints.isNotEmpty()) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Audio", fontWeight = FontWeight.SemiBold)
            state.endpoints.forEach { endpoint ->
                OutlinedButton(
                    onClick = { dispatch(PhoneAction.SelectEndpoint(endpoint.id)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(endpoint.label + if (endpoint.id == state.currentEndpointId) " • selected" else "") }
            }
        }
    }
    call.conferenceableIds.firstOrNull()?.let { otherId ->
        OutlinedButton(
            onClick = { dispatch(PhoneAction.Merge(call.id, otherId)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Merge calls") }
    }
    if (call.canSeparate) {
        OutlinedButton(onClick = { dispatch(PhoneAction.Split(call.id)) },
            modifier = Modifier.fillMaxWidth()) { Text("Separate call") }
    }
    if (call.canSwap) {
        OutlinedButton(onClick = { dispatch(PhoneAction.SwapConference(call.id)) },
            modifier = Modifier.fillMaxWidth()) { Text("Swap conference") }
    }
}

@Composable
private fun DialPad(onDigit: (Char) -> Unit, onLongDigit: (Char) -> Unit) {
    val rows = listOf("123", "456", "789", "*0#")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { digit ->
                    OutlinedButton(
                        onClick = { onDigit(digit) },
                        modifier = Modifier.weight(1f).height(54.dp),
                    ) { Text(digit.toString(), style = MaterialTheme.typography.titleLarge) }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    state: PhoneUiState,
    dispatch: (PhoneAction) -> Unit,
    close: () -> Unit,
) {
    Scaffold { insets ->
        Column(
            Modifier.fillMaxSize(),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(
                    start = 12.dp,
                    top = insets.calculateTopPadding() + 8.dp,
                    end = 20.dp,
                    bottom = 8.dp,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = close) { Text("Back") }
                Text("Phone settings", style = MaterialTheme.typography.headlineSmall)
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(
                    start = 20.dp,
                    top = 8.dp,
                    end = 20.dp,
                    bottom = insets.calculateBottomPadding() + 20.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Appearance", style = MaterialTheme.typography.titleMedium)
                        ThemePreference.entries.forEach { preference ->
                            OutlinedButton(
                                onClick = { dispatch(PhoneAction.ChangeTheme(preference)) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                val label = when (preference) {
                                    ThemePreference.SYSTEM -> "Follow system"
                                    ThemePreference.LIGHT -> "Light"
                                    ThemePreference.DARK -> "Dark"
                                }
                                Text(label + if (preference == state.themePreference) " • selected" else "")
                            }
                        }
                    }
                }
                if (!state.isDialerRoleHeld) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("Calling app setup", style = MaterialTheme.typography.titleMedium)
                            SettingSwitch(
                                title = "Show setup reminder",
                                detail = "Show the Preview mode card on the Phone home screen.",
                                checked = state.showDialerRolePrompt,
                                enabled = true,
                            ) {
                                dispatch(PhoneAction.ChangeDialerRolePromptVisible(it))
                            }
                        }
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("AI call assistant", style = MaterialTheme.typography.titleMedium)
                        val policy = state.assistantPolicy
                        if (policy.loading) Text("Loading assistant settings…")
                        policy.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        SettingSwitch(
                            title = "Process and transcribe calls",
                            detail = "English and Spanish run on device. Artifacts expire after 24 hours.",
                            checked = policy.processingEnabled,
                            enabled = policy.available && !policy.saving,
                        ) { dispatch(PhoneAction.ChangeProcessingEnabled(it)) }

                        SettingSwitch(
                            title = "Use caller history",
                            detail = "Privately use only the history categories you select below.",
                            checked = policy.callerHistoryEnabled,
                            enabled = policy.available && !policy.saving,
                        ) { dispatch(PhoneAction.ChangeCallerHistoryEnabled(it)) }
                        if (policy.callerHistoryEnabled) {
                            Text("History sources", fontWeight = FontWeight.SemiBold)
                            SettingSwitch(
                                title = "Messages",
                                detail = "Recent SMS and MMS with this caller.",
                                checked = policy.messageHistoryEnabled,
                                enabled = policy.available && !policy.saving,
                            ) { dispatch(PhoneAction.ChangeMessageHistoryEnabled(it)) }
                            SettingSwitch(
                                title = "Previous calls",
                                detail = "Call events and recent 24-hour AI summaries.",
                                checked = policy.callHistoryEnabled,
                                enabled = policy.available && !policy.saving,
                            ) { dispatch(PhoneAction.ChangeCallHistoryEnabled(it)) }
                            SettingSwitch(
                                title = "Sent photo descriptions",
                                detail = "Descriptions linked after a carrier-confirmed sent photo.",
                                checked = policy.photoHistoryEnabled,
                                enabled = policy.available && !policy.saving,
                            ) { dispatch(PhoneAction.ChangePhotoHistoryEnabled(it)) }
                        }

                        SettingSwitch(
                            title = "Auto AI answer",
                            detail = "Let the on-device receptionist answer eligible calls automatically.",
                            checked = policy.autoAnswerEnabled,
                            enabled = policy.available && !policy.saving,
                        ) { dispatch(PhoneAction.ChangeAutoAnswerEnabled(it)) }
                        if (policy.autoAnswerEnabled) {
                            Text("Which calls?", fontWeight = FontWeight.SemiBold)
                            AssistantPolicySemantics.SELECTABLE_AUTO_ANSWER_MODES.forEach { mode ->
                                val label = when (mode) {
                                    AssistantPolicySemantics.MODE_MISSED_ONLY ->
                                        "After I don't answer"
                                    AssistantPolicySemantics.MODE_UNKNOWN_ONLY ->
                                        "Unknown callers"
                                    else -> "Every non-emergency call"
                                }
                                OutlinedButton(
                                    onClick = { dispatch(PhoneAction.ChangeAnswerMode(mode)) },
                                    enabled = policy.available && !policy.saving,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(label + if (policy.answerMode == mode) " • selected" else "")
                                }
                            }
                            if (policy.answerMode == AssistantPolicySemantics.MODE_MISSED_ONLY) {
                                Text("Ring me before AI answers", fontWeight = FontWeight.SemiBold)
                                AssistantPolicySemantics.MISSED_DELAY_OPTIONS_MILLIS
                                    .chunked(4)
                                    .forEach { choices ->
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            choices.forEach { millis ->
                                                val onClick = {
                                                    dispatch(PhoneAction.ChangeMissedDelay(millis))
                                                }
                                                if (policy.missedDelayMillis == millis) {
                                                    Button(
                                                        onClick = onClick,
                                                        enabled = policy.available && !policy.saving,
                                                        modifier = Modifier.weight(1f),
                                                    ) { Text("${millis / 1_000L}s") }
                                                } else {
                                                    OutlinedButton(
                                                        onClick = onClick,
                                                        enabled = policy.available && !policy.saving,
                                                        modifier = Modifier.weight(1f),
                                                    ) { Text("${millis / 1_000L}s") }
                                                }
                                            }
                                            repeat(4 - choices.size) {
                                                Spacer(Modifier.weight(1f))
                                            }
                                        }
                                    }
                                Text(
                                    "AI answers only if the call is still ringing after this time.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                Text("Auto-answer delay", fontWeight = FontWeight.SemiBold)
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    AssistantPolicySemantics.DIRECT_ANSWER_DELAY_MODES
                                        .take(4)
                                        .forEachIndexed { index, mode ->
                                            val label = "${index + 1}s"
                                            if (policy.answerDelayMode == mode) {
                                                Button(
                                                    onClick = {
                                                        dispatch(
                                                            PhoneAction.ChangeAnswerDelayMode(mode),
                                                        )
                                                    },
                                                    enabled = policy.available && !policy.saving,
                                                    modifier = Modifier.weight(1f),
                                                ) { Text(label) }
                                            } else {
                                                OutlinedButton(
                                                    onClick = {
                                                        dispatch(
                                                            PhoneAction.ChangeAnswerDelayMode(mode),
                                                        )
                                                    },
                                                    enabled = policy.available && !policy.saving,
                                                    modifier = Modifier.weight(1f),
                                                ) { Text(label) }
                                            }
                                        }
                                }
                                val randomMode =
                                    AssistantPolicySemantics.DIRECT_ANSWER_DELAY_MODES.last()
                                if (policy.answerDelayMode == randomMode) {
                                    Button(
                                        onClick = {
                                            dispatch(
                                                PhoneAction.ChangeAnswerDelayMode(randomMode),
                                            )
                                        },
                                        enabled = policy.available && !policy.saving,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text("Random • selected") }
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            dispatch(
                                                PhoneAction.ChangeAnswerDelayMode(randomMode),
                                            )
                                        },
                                        enabled = policy.available && !policy.saving,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text("Random") }
                                }
                                Text(
                                    "Random chooses a new delay from 1.01 to 3.99 seconds for each eligible call.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        if (!policy.automaticAnswerAvailable) {
                            Text(
                                "Automatic answering is locked until caller-audio output passes device validation.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Button(
                            onClick = { dispatch(PhoneAction.SaveAssistantPolicy) },
                            enabled = policy.available && !policy.saving,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (policy.saving) "Saving…" else "Save assistant settings") }
                        if (!policy.available) {
                            OutlinedButton(
                                onClick = { dispatch(PhoneAction.ReloadAssistantPolicy) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Try again") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

fun callStateLabel(state: Int): String = when (state) {
    Call.STATE_NEW -> "Starting"
    Call.STATE_DIALING -> "Dialing"
    Call.STATE_RINGING -> "Incoming call"
    Call.STATE_HOLDING -> "On hold"
    Call.STATE_ACTIVE -> "Connected"
    Call.STATE_DISCONNECTED -> "Call ended"
    Call.STATE_CONNECTING -> "Connecting"
    Call.STATE_SELECT_PHONE_ACCOUNT -> "Choose SIM"
    else -> "Call in progress"
}
