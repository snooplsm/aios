package com.aios.messaging

import android.app.Application
import android.app.role.RoleManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.telephony.PhoneNumberUtils
import androidx.core.content.edit
import com.aios.messaging.context.CommunicationContextClient
import com.aios.messaging.data.MessagingRepository
import com.aios.messaging.model.ContextSnippetUiState
import com.aios.messaging.model.ConversationUiState
import com.aios.messaging.model.MessagePolicy
import com.aios.messaging.model.MessageUiState
import com.aios.messaging.model.MessageDeliveryState
import com.aios.messaging.model.MessageTransport
import com.aios.messaging.model.MessagingAction
import com.aios.messaging.model.MessagingUiState
import com.aios.messaging.model.SelectedPhotoUiState
import com.aios.messaging.model.ThemePreference
import com.aios.messaging.notifications.MessageNotificationCoordinator
import com.aios.messaging.mms.MmsEvent
import com.aios.messaging.mms.MmsTransport
import com.aios.messaging.mms.platform.MmsTransportFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Single-process UDF store. Provider work is delegated off the main thread. */
object MessagingRuntime {
    private const val PREFS = "messaging_ui"
    private const val THEME = "theme"
    private const val SHOW_ROLE = "show_sms_role_prompt"

    private val main = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(MessagingUiState())
    val state: StateFlow<MessagingUiState> = mutableState.asStateFlow()

    private lateinit var application: Application
    private lateinit var repository: MessagingRepository
    private lateinit var contextIndex: CommunicationContextClient
    private lateinit var notifications: MessageNotificationCoordinator
    private lateinit var mmsTransport: MmsTransport
    private var initialized = false

    fun initialize(value: Application) {
        if (initialized) return
        application = value
        repository = MessagingRepository(value)
        contextIndex = CommunicationContextClient(value).also { it.connect() }
        notifications = MessageNotificationCoordinator(value)
        mmsTransport = MmsTransportFactory.create(value, object : MmsTransport.Listener {
            override fun onCompleted(event: MmsEvent) = onMmsCompleted(event)
            override fun onFailed(message: String) {
                showNotice(message)
                refresh()
            }
        })
        val preferences = value.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val theme = runCatching {
            ThemePreference.valueOf(preferences.getString(THEME, null).orEmpty())
        }.getOrDefault(ThemePreference.SYSTEM)
        mutableState.value = mutableState.value.copy(
            theme = theme,
            showRolePrompt = preferences.getBoolean(SHOW_ROLE, true),
            isMmsAdmitted = mmsTransport.admitted,
        )
        initialized = true
        refreshRole()
    }

    fun dispatch(action: MessagingAction) = onMain {
        when (action) {
            MessagingAction.Refresh -> refresh()
            is MessagingAction.SelectConversation -> select(action.conversation)
            MessagingAction.CloseConversation -> reduce {
                it.copy(selected = null, messages = emptyList(), context = emptyList())
            }
            is MessagingAction.ChangeRecipient -> reduce {
                it.copy(recipientDraft = action.value.take(80))
            }
            MessagingAction.OpenRecipient -> openRecipient(mutableState.value.recipientDraft)
            is MessagingAction.ChangeBody -> reduce {
                it.copy(bodyDraft = action.value.take(MessagePolicy.MAX_BODY_CHARS))
            }
            MessagingAction.Send -> send()
            is MessagingAction.DeleteMessage -> delete(action.id, action.transport)
            is MessagingAction.SelectPhoto -> reduce {
                it.copy(selectedPhoto = SelectedPhotoUiState(action.uri, action.label))
            }
            MessagingAction.ClearPhoto -> reduce { it.copy(selectedPhoto = null) }
            is MessagingAction.ChangeTheme -> {
                application.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                    putString(THEME, action.value.name)
                }
                reduce { it.copy(theme = action.value) }
            }
            is MessagingAction.ChangeRolePromptVisible -> {
                application.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                    putBoolean(SHOW_ROLE, action.visible)
                }
                reduce { it.copy(showRolePrompt = action.visible) }
            }
            MessagingAction.ClearNotice -> reduce { it.copy(notice = null) }
        }
    }

    fun refreshRole() = onMain {
        if (!initialized) return@onMain
        val held = application.getSystemService(RoleManager::class.java)
            ?.isRoleHeld(RoleManager.ROLE_SMS) == true
        reduce { it.copy(isSmsRoleHeld = held) }
        if (held) refresh() else reduce {
            it.copy(loading = false, conversations = emptyList(), messages = emptyList())
        }
    }

    fun openAddress(address: String, initialBody: String = "") = onMain {
        if (initialBody.isNotBlank()) reduce {
            it.copy(bodyDraft = initialBody.take(MessagePolicy.MAX_BODY_CHARS))
        }
        openRecipient(address)
    }

    fun receiveSms(
        address: String,
        body: String,
        timestamp: Long,
        subscriptionId: Int,
        completion: (Boolean) -> Unit = {},
    ) {
        if (!initialized) {
            completion(false)
            return
        }
        repository.storeIncoming(address, body, timestamp, subscriptionId) { result ->
            result.onSuccess { message ->
                contextIndex.indexSms(message.id, message.address, message.body, message.atEpochMillis)
                notifications.notifyIncoming(message)
                refresh()
                if (mutableState.value.selected?.threadId == message.threadId) {
                    loadSelectedMessages(message.threadId)
                }
                completion(true)
            }.onFailure {
                showNotice("Incoming SMS could not be stored")
                completion(false)
            }
        }
    }

    fun sendQuickReply(address: String, body: String, completion: (Boolean) -> Unit) {
        if (!initialized) {
            completion(false)
            return
        }
        repository.sendSms(address, body) { result ->
            result.onSuccess { message ->
                contextIndex.indexSms(message.id, message.address, message.body, message.atEpochMillis)
                refresh()
                completion(true)
            }.onFailure {
                showNotice(it.message ?: "Quick reply could not be sent")
                completion(false)
            }
        }
    }

    fun receiveMms(
        pdu: ByteArray,
        subscriptionId: Int,
        completion: (Boolean) -> Unit,
    ) {
        if (!initialized || !mmsTransport.admitted) {
            showNotice("Incoming MMS requires a debuggable AIOS build until carrier gates pass")
            completion(false)
            return
        }
        mmsTransport.receiveWapPush(pdu, subscriptionId, completion)
    }

    fun completeMmsOperation(
        action: String,
        token: String,
        resultCode: Int,
        response: ByteArray?,
        httpStatus: Int,
        completion: (Boolean) -> Unit,
    ) {
        if (!initialized) {
            completion(false)
            return
        }
        mmsTransport.complete(action, token, resultCode, response, httpStatus, completion)
    }

    private fun refresh() {
        if (!mutableState.value.isSmsRoleHeld) return
        reduce { it.copy(loading = true) }
        repository.loadConversations { result ->
            result.fold(
                onSuccess = { conversations ->
                    reduce { current -> current.copy(loading = false, conversations = conversations) }
                },
                onFailure = { error ->
                    reduce { it.copy(loading = false, notice = error.message ?: "Messages unavailable") }
                },
            )
        }
    }

    private fun select(conversation: ConversationUiState) {
        reduce { it.copy(selected = conversation, messages = emptyList(), context = emptyList()) }
        repository.markThreadRead(conversation.threadId)
        loadSelectedMessages(conversation.threadId)
        contextIndex.queryRecent(conversation.address) { snippets ->
            if (mutableState.value.selected?.address == conversation.address) {
                reduce { current ->
                    current.copy(context = snippets.map {
                        ContextSnippetUiState(it.sourceType, it.eventAtEpochMillis, it.excerpt)
                    })
                }
            }
        }
    }

    private fun openRecipient(rawAddress: String) {
        val normalized = PhoneNumberUtils.normalizeNumber(rawAddress)
        if (normalized.isBlank()) {
            showNotice("Enter a valid phone number")
            return
        }
        val existing = mutableState.value.conversations.firstOrNull {
            PhoneNumberUtils.compare(it.address, normalized)
        }
        if (existing != null) {
            select(existing)
        } else {
            reduce {
                it.copy(
                    selected = ConversationUiState(
                        threadId = 0L,
                        address = normalized,
                        displayName = normalized,
                        lastBody = "",
                        lastAtEpochMillis = 0L,
                        unread = false,
                    ),
                    recipientDraft = "",
                    messages = emptyList(),
                    context = emptyList(),
                )
            }
            contextIndex.queryRecent(normalized) { snippets ->
                if (mutableState.value.selected?.address == normalized) reduce { current ->
                    current.copy(context = snippets.map {
                        ContextSnippetUiState(it.sourceType, it.eventAtEpochMillis, it.excerpt)
                    })
                }
            }
        }
    }

    private fun send() {
        val current = mutableState.value
        val conversation = current.selected ?: run {
            showNotice("Choose a conversation first")
            return
        }
        val body = MessagePolicy.normalizedBody(current.bodyDraft)
        val photo = current.selectedPhoto
        if (photo != null) {
            if (!current.isMmsAdmitted) {
                showNotice("Photo MMS is enabled only on debuggable builds until carrier tests pass")
                return
            }
            if (!MessagePolicy.requiresMms(body, hasPhoto = true)) {
                showNotice("The photo message is not valid")
                return
            }
            mmsTransport.sendPhoto(conversation.address, body, photo.uri) { result ->
                result.fold(
                    onSuccess = { event ->
                        reduce { it.copy(bodyDraft = "", selectedPhoto = null, notice = null) }
                        refresh()
                        if (event.threadId > 0L) {
                            select(conversation.copy(threadId = event.threadId))
                        }
                    },
                    onFailure = { showNotice(it.message ?: "Photo MMS could not be submitted") },
                )
            }
            return
        }
        if (!MessagePolicy.canSendSms(body, hasPhoto = false)) {
            showNotice("Write a message first")
            return
        }
        repository.sendSms(conversation.address, body) { result ->
            result.fold(
                onSuccess = { message ->
                    contextIndex.indexSms(message.id, message.address, message.body, message.atEpochMillis)
                    reduce { it.copy(bodyDraft = "", selectedPhoto = null, notice = null) }
                    refresh()
                    if (message.threadId > 0L) {
                        select(conversation.copy(threadId = message.threadId))
                    }
                },
                onFailure = { showNotice(it.message ?: "SMS could not be sent") },
            )
        }
    }

    private fun delete(id: Long, transport: MessageTransport) {
        repository.deleteMessage(id, transport) { result ->
            result.fold(
                onSuccess = {
                    if (transport == MessageTransport.SMS) {
                        contextIndex.deleteSms(id, System.currentTimeMillis())
                    } else {
                        contextIndex.deleteMms(id, System.currentTimeMillis())
                    }
                    mutableState.value.selected?.threadId?.let(::loadSelectedMessages)
                    refresh()
                },
                onFailure = { showNotice(it.message ?: "Message could not be deleted") },
            )
        }
    }

    private fun onMmsCompleted(event: MmsEvent) = onMain {
        indexMms(event)
        val message = event.toUiState()
        if (!event.outgoing) notifications.notifyIncoming(message)
        refresh()
        if (mutableState.value.selected?.threadId == event.threadId && event.threadId > 0L) {
            loadSelectedMessages(event.threadId)
        }
    }

    private fun indexMms(event: MmsEvent) {
        contextIndex.indexMms(
            event.providerId,
            event.address,
            event.text,
            event.atEpochMillis,
            event.hasPhoto,
        )
    }

    private fun MmsEvent.toUiState() = MessageUiState(
        id = providerId,
        threadId = threadId,
        address = address,
        body = text,
        atEpochMillis = atEpochMillis,
        outgoing = outgoing,
        read = outgoing,
        transport = MessageTransport.MMS,
        hasPhoto = hasPhoto,
        deliveryState = MessageDeliveryState.COMPLETE,
    )

    private fun loadSelectedMessages(threadId: Long) {
        if (threadId <= 0L) return
        repository.loadMessages(threadId) { result ->
            result.fold(
                onSuccess = { messages ->
                    if (mutableState.value.selected?.threadId == threadId) {
                        reduce { it.copy(messages = messages) }
                    }
                },
                onFailure = { showNotice(it.message ?: "Conversation unavailable") },
            )
        }
    }

    private fun showNotice(value: String) = onMain { reduce { it.copy(notice = value) } }

    private inline fun reduce(block: (MessagingUiState) -> MessagingUiState) {
        mutableState.value = block(mutableState.value)
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }
}
