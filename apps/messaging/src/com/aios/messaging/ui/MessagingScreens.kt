package com.aios.messaging.ui

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aios.messaging.model.ConversationUiState
import com.aios.messaging.model.MessageUiState
import com.aios.messaging.model.MessageDeliveryState
import com.aios.messaging.model.MessagingAction
import com.aios.messaging.model.MessagingUiState
import com.aios.messaging.model.SubscriptionUiState
import com.aios.messaging.model.ThemePreference

@Composable
fun MessagingScreen(
    state: MessagingUiState,
    dispatch: (MessagingAction) -> Unit,
    requestRole: () -> Unit,
    requestSubscriptionPermission: () -> Unit,
    pickPhoto: () -> Unit,
    call: (String) -> Unit,
) {
    if (state.selected == null) {
        ConversationList(state, dispatch, requestRole, requestSubscriptionPermission)
    } else {
        ConversationThread(state, dispatch, requestSubscriptionPermission, pickPhoto, call)
    }
}

@Composable
private fun ConversationList(
    state: MessagingUiState,
    dispatch: (MessagingAction) -> Unit,
    requestRole: () -> Unit,
    requestSubscriptionPermission: () -> Unit,
) {
    Scaffold { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "AIOS Messages",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("Calls, messages, and shared photos—on device")
                    }
                }
            }
            item { ThemePicker(state.theme, dispatch) }
            if (!state.isSmsRoleHeld && state.showRolePrompt) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("SMS role", fontWeight = FontWeight.SemiBold)
                                TextButton(onClick = {
                                    dispatch(MessagingAction.ChangeRolePromptVisible(false))
                                }) { Text("Dismiss") }
                            }
                            Text(
                                if (state.isMmsAdmitted) {
                                    "SMS and MMS are enabled for development testing. Carrier delivery " +
                                        "is not a passed release gate, so do not use this as your daily app yet."
                                } else {
                                    "SMS is ready for testing. MMS stays disabled on release builds until " +
                                        "the carrier/device gate passes."
                                },
                            )
                            Button(onClick = requestRole) { Text("Choose SMS app") }
                        }
                    }
                }
            }
            if (state.isSmsRoleHeld && state.needsSubscriptionPermission) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text("SIM access", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Allow phone-state access so AIOS Messages can list active SIMs " +
                                    "and route each message through the SIM you choose.",
                            )
                            Button(onClick = requestSubscriptionPermission) {
                                Text("Allow SIM access")
                            }
                        }
                    }
                }
            }
            state.notice?.let { notice ->
                item {
                    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(notice, modifier = Modifier.weight(1f))
                            TextButton(onClick = { dispatch(MessagingAction.ClearNotice) }) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
            }
            item {
                Text("New conversation", style = MaterialTheme.typography.titleMedium)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = state.recipientDraft,
                        onValueChange = { dispatch(MessagingAction.ChangeRecipient(it)) },
                        label = { Text("Phone number") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { dispatch(MessagingAction.OpenRecipient) }) { Text("Open") }
                }
            }
            item {
                Text("Conversations", style = MaterialTheme.typography.titleLarge)
                if (state.loading) Text("Loading…")
                if (state.isSmsRoleHeld && !state.loading && state.conversations.isEmpty()) {
                    Text("No SMS conversations yet")
                }
            }
            items(state.conversations, key = { it.threadId }) { conversation ->
                ConversationCard(conversation) {
                    dispatch(MessagingAction.SelectConversation(conversation))
                }
            }
        }
    }
}

@Composable
private fun ConversationCard(conversation: ConversationUiState, open: () -> Unit) {
    Card(onClick = open, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(conversation.displayName, fontWeight = FontWeight.SemiBold)
                Text(relativeTime(conversation.lastAtEpochMillis))
            }
            Text(conversation.lastBody.take(140), maxLines = 2)
            if (conversation.unread) {
                Text("Unread", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ConversationThread(
    state: MessagingUiState,
    dispatch: (MessagingAction) -> Unit,
    requestSubscriptionPermission: () -> Unit,
    pickPhoto: () -> Unit,
    call: (String) -> Unit,
) {
    val conversation = requireNotNull(state.selected)
    Scaffold(
        topBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { dispatch(MessagingAction.CloseConversation) }) {
                        Text("Conversations")
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(conversation.displayName, fontWeight = FontWeight.SemiBold)
                        Text(conversation.address, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { call(conversation.address) }) { Text("Call") }
                }
            }
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SubscriptionPicker(
                        subscriptions = state.subscriptions,
                        selectedSubscriptionId = state.selectedSubscriptionId,
                        needsPermission = state.needsSubscriptionPermission,
                        requestPermission = requestSubscriptionPermission,
                        dispatch = dispatch,
                    )
                    state.selectedPhoto?.let { photo ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Photo: ${photo.label}", modifier = Modifier.weight(1f))
                            TextButton(onClick = { dispatch(MessagingAction.ClearPhoto) }) {
                                Text("Remove")
                            }
                        }
                    }
                    OutlinedTextField(
                        value = state.bodyDraft,
                        onValueChange = { dispatch(MessagingAction.ChangeBody(it)) },
                        label = { Text("Message") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 5,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = pickPhoto, modifier = Modifier.weight(1f)) {
                            Text("Photo")
                        }
                        Button(
                            onClick = { dispatch(MessagingAction.Send) },
                            enabled = state.selectedSubscriptionId != null,
                            modifier = Modifier.weight(1f),
                        ) { Text(if (state.selectedPhoto == null) "Send SMS" else "Send photo") }
                    }
                }
            }
        },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.notice?.let { notice ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(notice, modifier = Modifier.weight(1f))
                            TextButton(onClick = { dispatch(MessagingAction.ClearNotice) }) {
                                Text("Dismiss")
                            }
                        }
                    }
                }
            }
            if (state.context.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("On-device context", fontWeight = FontWeight.SemiBold)
                            state.context.take(3).forEach { snippet ->
                                Text("${snippet.sourceType}: ${snippet.text}", maxLines = 2)
                            }
                        }
                    }
                }
            }
            items(state.messages, key = { "${it.transport}:${it.id}" }) { message ->
                val simLabel = message.subscriptionId?.let { subscriptionId ->
                    state.subscriptions.firstOrNull {
                        it.subscriptionId == subscriptionId
                    }?.label
                }
                MessageBubble(message, simLabel) {
                    dispatch(MessagingAction.DeleteMessage(message.id, message.transport))
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
    }
}

@Composable
private fun MessageBubble(message: MessageUiState, simLabel: String?, delete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start) {
            Box(
                Modifier.widthIn(max = 300.dp)
                    .background(
                        if (message.outgoing) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.shapes.large,
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Column {
                    if (message.hasPhoto) {
                        Text("Photo", fontWeight = FontWeight.SemiBold)
                    }
                    if (message.body != "[Photo]") Text(message.body)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(relativeTime(message.atEpochMillis), style = MaterialTheme.typography.bodySmall)
                if (simLabel != null && simLabel.isNotBlank()) {
                    Text(" - $simLabel", style = MaterialTheme.typography.bodySmall)
                }
                when (message.deliveryState) {
                    MessageDeliveryState.SENDING -> Text(" · Sending")
                    MessageDeliveryState.FAILED -> Text(" · Failed")
                    MessageDeliveryState.WAITING_DOWNLOAD -> Text(" · Waiting")
                    MessageDeliveryState.COMPLETE -> Unit
                }
                TextButton(onClick = delete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun SubscriptionPicker(
    subscriptions: List<SubscriptionUiState>,
    selectedSubscriptionId: Int?,
    needsPermission: Boolean,
    requestPermission: () -> Unit,
    dispatch: (MessagingAction) -> Unit,
) {
    Text("Send with", style = MaterialTheme.typography.labelLarge)
    when {
        subscriptions.isEmpty() && needsPermission -> OutlinedButton(
            onClick = requestPermission,
        ) { Text("Allow SIM access") }
        subscriptions.isEmpty() -> Text("No active SMS SIM")
        subscriptions.size == 1 -> Text(subscriptions.single().label)
        else -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            subscriptions.forEach { subscription ->
                val choose = {
                    dispatch(MessagingAction.SelectSubscription(subscription.subscriptionId))
                }
                if (subscription.subscriptionId == selectedSubscriptionId) {
                    Button(onClick = choose, modifier = Modifier.weight(1f)) {
                        Text(subscription.label, maxLines = 1)
                    }
                } else {
                    OutlinedButton(onClick = choose, modifier = Modifier.weight(1f)) {
                        Text(subscription.label, maxLines = 1)
                    }
                }
            }
        }
    }
    if (subscriptions.size > 1 && selectedSubscriptionId == null) {
        Text("Choose a SIM before sending", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ThemePicker(
    selected: ThemePreference,
    dispatch: (MessagingAction) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemePreference.entries.forEach { theme ->
            if (theme == selected) {
                Button(
                    onClick = { dispatch(MessagingAction.ChangeTheme(theme)) },
                    modifier = Modifier.weight(1f),
                ) { Text(theme.name.lowercase().replaceFirstChar { it.uppercase() }) }
            } else {
                OutlinedButton(
                    onClick = { dispatch(MessagingAction.ChangeTheme(theme)) },
                    modifier = Modifier.weight(1f),
                ) { Text(theme.name.lowercase().replaceFirstChar { it.uppercase() }) }
            }
        }
    }
}

private fun relativeTime(timestamp: Long): String = if (timestamp <= 0L) "" else
    DateUtils.getRelativeTimeSpanString(
        timestamp,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()
