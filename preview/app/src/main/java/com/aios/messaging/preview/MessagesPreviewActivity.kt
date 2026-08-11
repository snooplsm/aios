package com.aios.messaging.preview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aios.messaging.model.ContextSnippetUiState
import com.aios.messaging.model.ConversationUiState
import com.aios.messaging.model.MessageDeliveryState
import com.aios.messaging.model.MessageTransport
import com.aios.messaging.model.MessageUiState
import com.aios.messaging.model.MessagingAction
import com.aios.messaging.model.MessagingUiState
import com.aios.messaging.model.SelectedPhotoUiState
import com.aios.messaging.model.SubscriptionUiState
import com.aios.messaging.model.ThemePreference
import com.aios.messaging.ui.MessagingScreen
import com.aios.messaging.ui.theme.AiosMessagingTheme

private enum class MessagesPreviewScenario(val wireValue: String) {
    INBOX("inbox"),
    CONVERSATION("conversation"),
    CONTEXT_PHOTO("context-photo");

    companion object {
        fun fromWire(value: String?): MessagesPreviewScenario =
            entries.firstOrNull { it.wireValue == value } ?: INBOX
    }
}

/** Visual-only harness. It never requests the SMS role or contacts a carrier. */
class MessagesPreviewActivity : ComponentActivity() {
    private companion object {
        const val EXTRA_SCENARIO = "aios_messages_preview_scenario"
        const val EXTRA_THEME = "aios_messages_preview_theme"
    }

    private var state by mutableStateOf(mockState(MessagesPreviewScenario.INBOX))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scenario = MessagesPreviewScenario.fromWire(
            intent.getStringExtra(EXTRA_SCENARIO),
        )
        state = mockState(scenario).copy(theme = requestedTheme())
        setContent {
            BackHandler(enabled = state.selected != null) {
                dispatch(MessagingAction.CloseConversation)
            }
            AiosMessagingTheme(state.theme) {
                MessagingScreen(
                    state = state,
                    dispatch = ::dispatch,
                    requestRole = { notice("SMS role changes are disabled in this preview") },
                    requestSubscriptionPermission = {
                        notice("SIM permissions are disabled in this preview")
                    },
                    pickPhoto = {
                        dispatch(
                            MessagingAction.SelectPhoto(
                                "content://com.aios.preview/replacement-valve",
                                "replacement-valve.jpg",
                            ),
                        )
                    },
                    call = { notice("Calling $it is disabled in this preview") },
                )
            }
        }
    }

    private fun dispatch(action: MessagingAction) {
        state = when (action) {
            MessagingAction.Refresh -> state
            is MessagingAction.SelectConversation -> openConversation(state, action.conversation)
            MessagingAction.CloseConversation -> state.copy(
                selected = null,
                messages = emptyList(),
                context = emptyList(),
                selectedPhoto = null,
                bodyDraft = "",
                notice = null,
            )
            is MessagingAction.ChangeRecipient -> state.copy(recipientDraft = action.value)
            MessagingAction.OpenRecipient -> {
                val address = state.recipientDraft.trim()
                if (address.isEmpty()) {
                    state.copy(notice = "Enter a phone number")
                } else {
                    openConversation(
                        state,
                        ConversationUiState(
                            threadId = 99,
                            address = address,
                            displayName = address,
                            lastBody = "",
                            lastAtEpochMillis = 0,
                            unread = false,
                        ),
                    )
                }
            }
            is MessagingAction.ChangeBody -> state.copy(bodyDraft = action.value)
            MessagingAction.Send -> queuePreviewMessage(state)
            is MessagingAction.DeleteMessage -> state.copy(
                messages = state.messages.filterNot {
                    it.id == action.id && it.transport == action.transport
                },
            )
            is MessagingAction.SelectPhoto -> state.copy(
                selectedPhoto = SelectedPhotoUiState(action.uri, action.label),
            )
            MessagingAction.ClearPhoto -> state.copy(selectedPhoto = null)
            is MessagingAction.SelectSubscription -> state.copy(
                selectedSubscriptionId = action.subscriptionId,
            )
            is MessagingAction.ChangeTheme -> state.copy(theme = action.value)
            is MessagingAction.ChangeRolePromptVisible -> state.copy(
                showRolePrompt = action.visible,
            )
            MessagingAction.ClearNotice -> state.copy(notice = null)
        }
    }

    private fun notice(value: String) {
        state = state.copy(notice = value)
    }

    private fun requestedTheme(): ThemePreference = when (
        intent.getStringExtra(EXTRA_THEME)?.lowercase()
    ) {
        "light" -> ThemePreference.LIGHT
        "dark" -> ThemePreference.DARK
        else -> ThemePreference.SYSTEM
    }

    private fun queuePreviewMessage(current: MessagingUiState): MessagingUiState {
        val conversation = current.selected
            ?: return current.copy(notice = "Open a conversation first")
        val body = current.bodyDraft.trim()
        val hasPhoto = current.selectedPhoto != null
        if (body.isEmpty() && !hasPhoto) return current.copy(notice = "Write a message first")
        val message = MessageUiState(
            id = (current.messages.maxOfOrNull { it.id } ?: 0L) + 1L,
            threadId = conversation.threadId,
            address = conversation.address,
            body = body.ifEmpty { "[Photo]" },
            atEpochMillis = System.currentTimeMillis(),
            outgoing = true,
            read = true,
            transport = if (hasPhoto) MessageTransport.MMS else MessageTransport.SMS,
            hasPhoto = hasPhoto,
            deliveryState = MessageDeliveryState.SENDING,
            subscriptionId = current.selectedSubscriptionId,
        )
        return current.copy(
            messages = current.messages + message,
            bodyDraft = "",
            selectedPhoto = null,
            notice = "Message queued in the safe preview",
        )
    }
}

private fun mockState(scenario: MessagesPreviewScenario): MessagingUiState {
    val now = System.currentTimeMillis()
    val conversations = listOf(
        ConversationUiState(
            11,
            "+1 555 010 2841",
            "Maria Alvarez",
            "Yes, tomorrow at 8 works.",
            now - 5 * 60_000,
            unread = true,
            subscriptionId = 1,
        ),
        ConversationUiState(
            12,
            "+1 555 010 6620",
            "Westside Supply",
            "Your order is ready for pickup.",
            now - 55 * 60_000,
            unread = false,
            subscriptionId = 1,
        ),
        ConversationUiState(
            13,
            "+1 555 010 4108",
            "Jake - crew",
            "Finished the Franklin Street repair.",
            now - 2 * 60 * 60_000,
            unread = false,
            subscriptionId = 1,
        ),
    )
    val base = MessagingUiState(
        isSmsRoleHeld = true,
        isMmsAdmitted = true,
        showRolePrompt = false,
        conversations = conversations,
        subscriptions = listOf(SubscriptionUiState(1, "Business SIM", 0, false)),
        selectedSubscriptionId = 1,
    )
    if (scenario == MessagesPreviewScenario.INBOX) return base
    val open = openConversation(base, conversations.first())
    return if (scenario == MessagesPreviewScenario.CONTEXT_PHOTO) {
        open.copy(
            bodyDraft = "This is the replacement valve I mentioned.",
            selectedPhoto = SelectedPhotoUiState(
                "content://com.aios.preview/replacement-valve",
                "replacement-valve.jpg",
            ),
        )
    } else {
        open
    }
}

private fun openConversation(
    current: MessagingUiState,
    conversation: ConversationUiState,
): MessagingUiState {
    val now = System.currentTimeMillis()
    val isMaria = conversation.threadId == 11L
    return current.copy(
        selected = conversation,
        messages = if (isMaria) {
            listOf(
                MessageUiState(
                    101,
                    conversation.threadId,
                    conversation.address,
                    "Hi, the water heater is leaking again.",
                    now - 38 * 60_000,
                    outgoing = false,
                    read = true,
                    subscriptionId = 1,
                ),
                MessageUiState(
                    102,
                    conversation.threadId,
                    conversation.address,
                    "I can come by tomorrow morning.",
                    now - 20 * 60_000,
                    outgoing = true,
                    read = true,
                    subscriptionId = 1,
                ),
                MessageUiState(
                    103,
                    conversation.threadId,
                    conversation.address,
                    "Yes, tomorrow at 8 works.",
                    now - 5 * 60_000,
                    outgoing = false,
                    read = true,
                    subscriptionId = 1,
                ),
            )
        } else {
            emptyList()
        },
        context = if (isMaria) {
            listOf(
                ContextSnippetUiState(
                    "Call",
                    now - 42 * 60_000,
                    "Requested a water-heater repair at 18 Franklin Street.",
                ),
                ContextSnippetUiState(
                    "Photo",
                    now - 35 * 60_000,
                    "Corroded inlet fitting and model plate from the recent job photo.",
                ),
            )
        } else {
            emptyList()
        },
        recipientDraft = "",
        bodyDraft = "",
        selectedPhoto = null,
        notice = null,
    )
}
