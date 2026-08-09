package com.aios.phone.intelligence

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.util.Base64
import androidx.core.content.edit
import com.aios.call.CallHandlingDecision
import com.aios.call.CallAssistantPolicy
import com.aios.call.CallRiskAssessment
import com.aios.call.CallAssistantState
import com.aios.call.IAiosCallIntelligence
import com.aios.call.ICallIntelligenceListener
import com.aios.call.IncomingCallContext
import com.aios.call.TranscriptSegment
import com.aios.phone.model.CallUiState
import com.aios.phone.model.AssistantCallSemantics
import com.aios.phone.model.AssistantPolicyUiState
import com.aios.phone.model.AssistantCallUiState
import com.aios.phone.model.CallRiskLabel
import com.aios.phone.model.CallRiskSemantics
import com.aios.phone.model.CallRiskSource
import com.aios.phone.model.RiskUiState
import com.aios.phone.model.TranscriptUiState
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Fail-open-for-telephony client for the optional privileged intelligence process. */
class CallAssistantClient(
    private val context: Context,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onAssistantConnectionChanged(connected: Boolean)
        fun onTranscript(callId: String, segment: TranscriptUiState)
        fun onRisk(callId: String, risk: RiskUiState)
        fun onAssistantCallState(callId: String, state: AssistantCallUiState)
        fun onAiAnswerRequested(callId: String)
        fun onTakeOverResult(callId: String, succeeded: Boolean)
        fun onAssistantFailure(callId: String, status: Int, detail: String)
        fun onPolicyChanged(policy: AssistantPolicyUiState)
    }

    private data class Session(
        val callId: String,
        val address: String,
        val direction: Int,
        var state: Int,
        var emergencyCallbackMode: Boolean,
        val networkIdentifiedEmergency: Boolean,
        var processingAllowed: Boolean? = null,
        var decisionRequested: Boolean = false,
        var answeredByAi: Boolean = false,
        var answeredNotified: Boolean = false,
        var assistantRevision: Long = 0L,
        var delayedAnswer: Runnable? = null,
    )

    private val main = Handler(Looper.getMainLooper())
    private val telecomLifecycleToken: IBinder = Binder()
    private val pendingAiAnswers = PendingAiAnswerGate()
    private val pendingTakeovers = mutableSetOf<String>()
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { work ->
        Thread(work, "aios-phone-intelligence")
    }
    private val sessions = linkedMapOf<String, Session>()
    private var remote: IAiosCallIntelligence? = null
    private var bound = false
    private var started = false
    private var ownerProcessingEnabled: Boolean? = null

    private val listener = object : ICallIntelligenceListener.Stub() {
        override fun onTranscript(segment: TranscriptSegment?) {
            if (segment == null || segment.callId.isNullOrBlank()) return
            val safe = TranscriptUiState(
                direction = segment.direction.orEmpty().take(16),
                language = segment.language.orEmpty().take(8),
                text = segment.text.orEmpty().take(MAX_TRANSCRIPT_CHARS),
                isFinal = segment.isFinal,
                startMillis = segment.startMillis,
            )
            main.post { if (sessions.containsKey(segment.callId)) callbacks.onTranscript(segment.callId, safe) }
        }

        override fun onRiskChanged(assessment: CallRiskAssessment?) {
            val callId = assessment?.callId?.takeIf {
                it.isNotBlank() && it.length <= MAX_CALL_ID_CHARS
            } ?: return
            val label = CallRiskLabel.fromWire(assessment.label) ?: return
            val source = CallRiskSource.fromWire(assessment.source) ?: return
            val reasonCode = assessment.reasonCode ?: return
            if (!label.accepts(assessment.riskScore)
                || !CallRiskSemantics.isValidReasonCode(reasonCode)
                || assessment.revision <= 0L
                || assessment.observedAtEpochMillis <= 0L
            ) return
            val safe = RiskUiState(
                score = assessment.riskScore,
                label = label,
                reasonCode = reasonCode,
                source = source,
                revision = assessment.revision,
                observedAtEpochMillis = assessment.observedAtEpochMillis,
            )
            main.post { if (sessions.containsKey(callId)) callbacks.onRisk(callId, safe) }
        }

        override fun onAssistantStateChanged(state: CallAssistantState?) {
            val callId = state?.callId?.takeIf {
                it.isNotBlank() && it.length <= MAX_CALL_ID_CHARS
            } ?: return
            if (state.revision <= 0L || state.observedAtEpochMillis <= 0L) return
            val safe = AssistantCallUiState(
                aiHandling = state.aiHandling,
                revision = state.revision,
                observedAtEpochMillis = state.observedAtEpochMillis,
            )
            main.post {
                sessions[callId]?.let { session ->
                    if (!AssistantCallSemantics.shouldReplace(
                            session.assistantRevision,
                            safe.revision,
                        )
                    ) return@post
                    session.assistantRevision = safe.revision
                    session.answeredByAi = safe.aiHandling
                    callbacks.onAssistantCallState(callId, safe)
                }
            }
        }

        override fun onServiceStatus(callId: String?, status: Int, detail: String?) {
            if (callId == "availability" && detail?.startsWith("speech_synthesis_") == true) {
                main.post { loadPolicy() }
                return
            }
            if (status < 0 && !callId.isNullOrBlank()) {
                val safeDetail = detail.orEmpty().take(MAX_STATUS_DETAIL_CHARS)
                main.post {
                    if (sessions.containsKey(callId)) {
                        callbacks.onAssistantFailure(callId, status, safeDetail)
                    }
                }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val service = IAiosCallIntelligence.Stub.asInterface(binder)
            remote = service
            worker.execute {
                try {
                    service.registerListener(listener)
                    val policy = service.policy
                    val processing = policy.processingEnabled
                    main.post {
                        if (remote === service) {
                            ownerProcessingEnabled = processing
                            callbacks.onAssistantConnectionChanged(true)
                            callbacks.onPolicyChanged(policy.toUi())
                            announceEveryPresentCall(service)
                            sessions.values.forEach { session ->
                                if (session.direction != Call.Details.DIRECTION_INCOMING) {
                                    session.processingAllowed = processing
                                    maybeNotifyAnswered(session)
                                } else {
                                    requestIncomingDecision(session)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    main.post { disconnect(service) }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = disconnect(remote)
        override fun onBindingDied(name: ComponentName) = disconnect(remote)
        override fun onNullBinding(name: ComponentName) = disconnect(remote)
    }

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (started) return
        started = true
        val intent = Intent(SERVICE_ACTION).setPackage(SERVICE_PACKAGE)
        bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        if (!bound) callbacks.onAssistantConnectionChanged(false)
    }

    fun stop() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (!started) return
        started = false
        val presentCallIds = sessions.keys.toList()
        sessions.values.forEach(::cancelDelayedAnswer)
        sessions.clear()
        pendingAiAnswers.clear()
        val service = remote
        remote = null
        callbacks.onAssistantConnectionChanged(false)
        if (service != null) worker.execute {
            try {
                presentCallIds.forEach { callId ->
                    service.setTelecomCallPresent(telecomLifecycleToken, callId, false)
                }
                service.unregisterListener(listener)
            } catch (_: Exception) {
                // The optional process may already be dead.
            }
        }
        if (bound) {
            runCatching { context.unbindService(connection) }
            bound = false
        }
    }

    fun onCallAdded(callId: String, call: Call) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val details = call.details
        val session = Session(
            callId = callId,
            address = if (details.handlePresentation == TelecomManager.PRESENTATION_ALLOWED) {
                details.handle?.schemeSpecificPart.orEmpty().take(MAX_ADDRESS_CHARS)
            } else {
                ""
            },
            direction = if (details.state == Call.STATE_RINGING) {
                Call.Details.DIRECTION_INCOMING
            } else {
                details.callDirection
            },
            state = details.state,
            emergencyCallbackMode = isPotentialEmergencyCallback(details),
            networkIdentifiedEmergency = details.hasProperty(
                Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL,
            ),
        )
        sessions[callId] = session
        announceCallPresence(session.callId, true)
        if (session.direction == Call.Details.DIRECTION_INCOMING) {
            requestIncomingDecision(session)
        } else {
            session.processingAllowed = ownerProcessingEnabled
            maybeNotifyAnswered(session)
        }
    }

    fun onCallsChanged(values: List<CallUiState>) {
        check(Looper.myLooper() == Looper.getMainLooper())
        values.forEach { value ->
            val session = sessions[value.id] ?: return@forEach
            if (value.properties and Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE != 0) {
                session.emergencyCallbackMode = true
            }
            if (session.state != value.state) {
                session.state = value.state
                if (value.state != Call.STATE_RINGING) cancelDelayedAnswer(session)
                maybeNotifyAnswered(session)
            }
        }
    }

    fun onCallRemoved(callId: String, disconnectCause: Int) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val session = sessions.remove(callId) ?: return
        pendingTakeovers.remove(callId)
        cancelDelayedAnswer(session)
        val service = remote ?: return
        worker.execute {
            try {
                service.setTelecomCallPresent(telecomLifecycleToken, callId, false)
                service.onCallEnded(callId, disconnectCause)
            } catch (_: Exception) {
                // Telephony has already ended; cleanup is best effort here.
            }
        }
    }

    fun markAiAnswered(callId: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        sessions[callId]?.let { session ->
            cancelDelayedAnswer(session)
            session.answeredByAi = true
        }
    }

    fun cancelAutomaticAnswer(callId: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        sessions[callId]?.let(::cancelDelayedAnswer)
    }

    fun takeOver(callId: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val service = remote
        if (service == null || !sessions.containsKey(callId)) {
            callbacks.onTakeOverResult(callId, false)
            return
        }
        if (!pendingTakeovers.add(callId)) return
        worker.execute {
            val succeeded = try {
                service.takeOverCall(callId)
            } catch (_: Exception) {
                false
            }
            main.post {
                pendingTakeovers.remove(callId)
                sessions[callId]?.let { session ->
                    if (succeeded) session.answeredByAi = false
                    callbacks.onTakeOverResult(callId, succeeded)
                }
            }
        }
    }

    fun loadPolicy() {
        val service = remote
        if (service == null) {
            callbacks.onPolicyChanged(
                AssistantPolicyUiState(error = "Call-assistant service is unavailable"),
            )
            return
        }
        callbacks.onPolicyChanged(AssistantPolicyUiState(loading = true))
        worker.execute {
            try {
                val policy = service.policy
                main.post {
                    if (remote === service) {
                        ownerProcessingEnabled = policy.processingEnabled
                        callbacks.onPolicyChanged(policy.toUi())
                    }
                }
            } catch (_: Exception) {
                main.post {
                    callbacks.onPolicyChanged(
                        AssistantPolicyUiState(error = "Could not read assistant settings"),
                    )
                }
            }
        }
    }

    fun savePolicy(value: AssistantPolicyUiState) {
        val service = remote
        if (service == null) {
            callbacks.onPolicyChanged(value.copy(
                available = false,
                saving = false,
                error = "Call-assistant service is unavailable",
            ))
            return
        }
        callbacks.onPolicyChanged(value.copy(saving = true, error = null))
        worker.execute {
            try {
                val requested = CallAssistantPolicy().apply {
                    answerMode = value.answerMode
                    answerDelayMode = value.answerDelayMode
                    missedDelayMillis = value.missedDelayMillis.coerceIn(3_000L, 60_000L)
                    processingEnabled = value.processingEnabled
                }
                val saved = service.updatePolicy(requested)
                main.post {
                    if (remote === service) {
                        ownerProcessingEnabled = saved.processingEnabled
                        callbacks.onPolicyChanged(saved.toUi())
                    }
                }
            } catch (_: Exception) {
                main.post {
                    callbacks.onPolicyChanged(value.copy(
                        available = true,
                        saving = false,
                        error = "Could not save assistant settings",
                    ))
                }
            }
        }
    }

    private fun requestIncomingDecision(session: Session) {
        val service = remote ?: return
        if (session.decisionRequested || session.state != Call.STATE_RINGING) return
        session.decisionRequested = true
        worker.execute {
            val knownContact = isKnownContact(session.address)
            val emergency = session.networkIdentifiedEmergency || isEmergency(session.address)
            val contextValue = IncomingCallContext().apply {
                callId = session.callId
                normalizedAddressHash = addressHash(session.address)
                this.knownContact = knownContact
                this.emergency = emergency
                this.emergencyCallbackMode = session.emergencyCallbackMode
                ringingSinceElapsedRealtimeMillis = SystemClock.elapsedRealtime()
            }
            try {
                val decision = service.evaluateIncoming(contextValue)
                main.post { applyDecision(session.callId, decision) }
            } catch (_: Exception) {
                main.post {
                    sessions[session.callId]?.decisionRequested = false
                    if (remote === service) disconnect(service)
                }
            }
        }
    }

    private fun announceEveryPresentCall(service: IAiosCallIntelligence) {
        val callIds = sessions.keys.toList()
        if (callIds.isEmpty()) return
        worker.execute {
            try {
                callIds.forEach { callId ->
                    service.setTelecomCallPresent(telecomLifecycleToken, callId, true)
                }
            } catch (_: Exception) {
                main.post { disconnect(service) }
            }
        }
    }

    private fun announceCallPresence(callId: String, present: Boolean) {
        val service = remote ?: return
        worker.execute {
            try {
                service.setTelecomCallPresent(telecomLifecycleToken, callId, present)
            } catch (_: Exception) {
                main.post { disconnect(service) }
            }
        }
    }

    private fun applyDecision(callId: String, decision: CallHandlingDecision?) {
        val session = sessions[callId] ?: return
        if (decision == null) {
            session.processingAllowed = false
            return
        }
        session.processingAllowed = decision.processingAllowed
        maybeNotifyAnswered(session)
        if (!decision.aiMayAnswer || session.state != Call.STATE_RINGING) return
        val delay = when (decision.action) {
            CallHandlingDecision.ACTION_ANSWER_WITH_AI ->
                decision.answerDelayMillis.coerceAtLeast(0L)
            CallHandlingDecision.ACTION_RING_THEN_AI -> decision.answerDelayMillis.coerceAtLeast(0L)
            else -> return
        }
        cancelDelayedAnswer(session)
        val reservation = pendingAiAnswers.arm(callId)
        val task = Runnable {
            val current = sessions[callId] ?: return@Runnable
            current.delayedAnswer = null
            if (!pendingAiAnswers.consume(callId, reservation)) return@Runnable
            if (current.state == Call.STATE_RINGING && current.processingAllowed == true) {
                callbacks.onAiAnswerRequested(callId)
            }
        }
        session.delayedAnswer = task
        if (!main.postDelayed(task, delay)) cancelDelayedAnswer(session)
    }

    private fun maybeNotifyAnswered(session: Session) {
        if (session.answeredNotified || session.state != Call.STATE_ACTIVE) return
        val processing = session.processingAllowed ?: return
        val service = remote ?: return
        session.answeredNotified = true
        worker.execute {
            try {
                service.onCallAnswered(session.callId, session.answeredByAi, processing)
            } catch (_: Exception) {
                // The call continues without AI if its optional process fails.
            }
        }
    }

    private fun cancelDelayedAnswer(session: Session) {
        pendingAiAnswers.cancel(session.callId)
        session.delayedAnswer?.let(main::removeCallbacks)
        session.delayedAnswer = null
    }

    private fun disconnect(expected: IAiosCallIntelligence?) {
        main.post {
            if (expected != null && remote !== expected) return@post
            remote = null
            ownerProcessingEnabled = null
            sessions.values.forEach { session ->
                cancelDelayedAnswer(session)
                if (session.state == Call.STATE_RINGING) {
                    session.decisionRequested = false
                    session.processingAllowed = null
                }
            }
            callbacks.onAssistantConnectionChanged(false)
        }
    }

    private fun isKnownContact(number: String): Boolean {
        if (number.isBlank()) return false
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number),
        )
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null,
                null,
                null,
            )
            cursor?.moveToFirst() == true
        } catch (_: SecurityException) {
            false
        } finally {
            cursor?.close()
        }
    }

    @Suppress("DEPRECATION")
    private fun isEmergency(number: String): Boolean =
        number.isNotBlank() && runCatching { PhoneNumberUtils.isEmergencyNumber(number) }
            .getOrDefault(true)

    private fun isPotentialEmergencyCallback(details: Call.Details): Boolean {
        if (details.hasProperty(Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE)) return true
        val lastEmergencyCall = details.extras
            ?.getLong(Call.EXTRA_LAST_EMERGENCY_CALLBACK_TIME_MILLIS, 0L)
            ?: 0L
        return lastEmergencyCall > 0L &&
            System.currentTimeMillis() - lastEmergencyCall in 0..EMERGENCY_CALLBACK_WINDOW_MILLIS
    }

    private fun addressHash(number: String): String {
        val normalized = PhoneNumberUtils.normalizeNumber(number)
        if (normalized.isBlank()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(installSalt())
        digest.update(normalized.toByteArray(StandardCharsets.UTF_8))
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    @SuppressLint("ApplySharedPref")
    private fun installSalt(): ByteArray {
        val preferences = context.getSharedPreferences(SALT_PREFS, Context.MODE_PRIVATE)
        preferences.getString(SALT_KEY, null)?.let { encoded ->
            runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()?.let { return it }
        }
        val value = ByteArray(32).also(SecureRandom()::nextBytes)
        // The first digest must not race an asynchronous salt write; a changed
        // salt would break the privacy-preserving per-install caller identity.
        preferences.edit(commit = true) {
            putString(SALT_KEY, Base64.encodeToString(value, Base64.NO_WRAP))
        }
        return value
    }

    private fun CallAssistantPolicy.toUi(): AssistantPolicyUiState =
        AssistantPolicyUiState(
            available = true,
            loading = false,
            saving = false,
            processingEnabled = processingEnabled,
            answerMode = answerMode,
            answerDelayMode = answerDelayMode,
            missedDelayMillis = missedDelayMillis,
            automaticAnswerAvailable = automaticAnswerAvailable,
            automaticAnswerUnavailableReason = automaticAnswerUnavailableReason,
            error = null,
        )

    private companion object {
        const val MAX_CALL_ID_CHARS = 128
        const val SERVICE_ACTION = "com.aios.call.CALL_INTELLIGENCE_SERVICE"
        const val SERVICE_PACKAGE = "com.aios.callintelligence"
        const val SALT_PREFS = "call_privacy"
        const val SALT_KEY = "address_hash_salt"
        const val MAX_ADDRESS_CHARS = 256
        const val MAX_TRANSCRIPT_CHARS = 512
        const val MAX_STATUS_DETAIL_CHARS = 160
        const val EMERGENCY_CALLBACK_WINDOW_MILLIS = 5 * 60 * 1000L
    }
}
