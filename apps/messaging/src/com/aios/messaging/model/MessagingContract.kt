package com.aios.messaging.model

data class MessagingUiState(
    val isSmsRoleHeld: Boolean = false,
    val showRolePrompt: Boolean = true,
    val loading: Boolean = false,
    val conversations: List<ConversationUiState> = emptyList(),
    val selected: ConversationUiState? = null,
    val messages: List<MessageUiState> = emptyList(),
    val context: List<ContextSnippetUiState> = emptyList(),
    val recipientDraft: String = "",
    val bodyDraft: String = "",
    val selectedPhoto: SelectedPhotoUiState? = null,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val notice: String? = null,
)

data class ConversationUiState(
    val threadId: Long,
    val address: String,
    val displayName: String,
    val lastBody: String,
    val lastAtEpochMillis: Long,
    val unread: Boolean,
)

data class MessageUiState(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val atEpochMillis: Long,
    val outgoing: Boolean,
    val read: Boolean,
)

data class ContextSnippetUiState(
    val sourceType: String,
    val atEpochMillis: Long,
    val text: String,
)

data class SelectedPhotoUiState(val uri: String, val label: String)

enum class ThemePreference { SYSTEM, LIGHT, DARK }

sealed interface MessagingAction {
    data object Refresh : MessagingAction
    data class SelectConversation(val conversation: ConversationUiState) : MessagingAction
    data object CloseConversation : MessagingAction
    data class ChangeRecipient(val value: String) : MessagingAction
    data object OpenRecipient : MessagingAction
    data class ChangeBody(val value: String) : MessagingAction
    data object Send : MessagingAction
    data class DeleteMessage(val id: Long) : MessagingAction
    data class SelectPhoto(val uri: String, val label: String) : MessagingAction
    data object ClearPhoto : MessagingAction
    data class ChangeTheme(val value: ThemePreference) : MessagingAction
    data class ChangeRolePromptVisible(val visible: Boolean) : MessagingAction
    data object ClearNotice : MessagingAction
}
