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
import android.os.RemoteException
import android.os.SystemClock
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
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
import java.util.Locale
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
        val emergency: EmergencyProcessingGate,
        var processingAllowed: Boolean? = null,
        var knownContact: Boolean? = null,
        var decisionRequested: Boolean = false,
        var answeredByAi: Boolean = false,
        var answeredNotified: Boolean = false,
        val riskRevisions: ServiceGenerationRevisionGate = ServiceGenerationRevisionGate(),
        val assistantRevisions: ServiceGenerationRevisionGate = ServiceGenerationRevisionGate(),
        var delayedAnswer: Runnable? = null,
    )

    private data class ServiceLease(
        val service: IAiosCallIntelligence,
        val connection: AssistantServiceConnection,
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
    private var rebindPolicy = PhoneServiceRebindPolicy()
    private var activeConnection: AssistantServiceConnection? = null
    private var rebindTask: Runnable? = null
    private var bindingWatchdog: Runnable? = null
    private var connectionGeneration = 0L
    private var connectionReady = false
    private var started = false
    private var ownerProcessingEnabled: Boolean? = null

    private fun createListener(connection: AssistantServiceConnection) =
        object : ICallIntelligenceListener.Stub() {
            override fun onTranscript(segment: TranscriptSegment?) {
                if (segment == null || segment.callId.isNullOrBlank()) return
                val callId = segment.callId
                val safe = TranscriptUiState(
                    direction = segment.direction.orEmpty().take(16),
                    language = segment.language.orEmpty().take(8),
                    text = segment.text.orEmpty().take(MAX_TRANSCRIPT_CHARS),
                    isFinal = segment.isFinal,
                    startMillis = segment.startMillis,
                )
                main.post {
                    if (isCurrentListener(connection) && sessions.containsKey(callId)) {
                        callbacks.onTranscript(callId, safe)
                    }
                }
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
                main.post {
                    if (!isCurrentListener(connection)) return@post
                    val session = sessions[callId] ?: return@post
                    val visibleRevision = session.riskRevisions.accept(assessment.revision)
                        ?: return@post
                    val safe = RiskUiState(
                        score = assessment.riskScore,
                        label = label,
                        reasonCode = reasonCode,
                        source = source,
                        revision = visibleRevision,
                        observedAtEpochMillis = assessment.observedAtEpochMillis,
                    )
                    callbacks.onRisk(callId, safe)
                }
            }

            override fun onAssistantStateChanged(state: CallAssistantState?) {
                val callId = state?.callId?.takeIf {
                    it.isNotBlank() && it.length <= MAX_CALL_ID_CHARS
                } ?: return
                if (state.revision <= 0L || state.observedAtEpochMillis <= 0L) return
                main.post {
                    if (!isCurrentListener(connection)) return@post
                    val session = sessions[callId] ?: return@post
                    val visibleRevision = session.assistantRevisions.accept(state.revision)
                        ?: return@post
                    val safe = AssistantCallUiState(
                        aiHandling = state.aiHandling,
                        revision = visibleRevision,
                        observedAtEpochMillis = state.observedAtEpochMillis,
                    )
                    session.answeredByAi = safe.aiHandling
                    callbacks.onAssistantCallState(callId, safe)
                }
            }

            override fun onServiceStatus(callId: String?, status: Int, detail: String?) {
                if (callId == "availability" && detail?.startsWith("speech_synthesis_") == true) {
                    main.post { if (isCurrentListener(connection)) loadPolicy() }
                    return
                }
                if (status < 0 && !callId.isNullOrBlank()) {
                    val safeDetail = detail.orEmpty().take(MAX_STATUS_DETAIL_CHARS)
                    main.post {
                        if (isCurrentListener(connection) && sessions.containsKey(callId)) {
                            callbacks.onAssistantFailure(callId, status, safeDetail)
                        }
                    }
                }
            }
        }

    private inner class AssistantServiceConnection(
        val generation: Long,
    ) : ServiceConnection {
        val listener: ICallIntelligenceListener = createListener(this)
        var service: IAiosCallIntelligence? = null

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            handleServiceConnected(this, binder)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            handleServiceDisconnected(this)
        }

        override fun onBindingDied(name: ComponentName) {
            terminateBinding(this, immediate = true)
        }

        override fun onNullBinding(name: ComponentName) {
            terminateBinding(this, immediate = true)
        }
    }

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (started) return
        started = true
        rebindPolicy = PhoneServiceRebindPolicy()
        scheduleRebind(immediate = true)
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
        connectionReady = false
        ownerProcessingEnabled = null
        cancelBindingWatchdog()
        rebindTask?.let(main::removeCallbacks)
        rebindTask = null
        rebindPolicy.close()
        val connection = activeConnection
        activeConnection = null
        callbacks.onAssistantConnectionChanged(false)
        val registeredListener = connection?.listener
        if (service != null && registeredListener != null) worker.execute {
            try {
                presentCallIds.forEach { callId ->
                    service.setTelecomCallPresent(telecomLifecycleToken, callId, false)
                }
                service.unregisterListener(registeredListener)
            } catch (_: Exception) {
                // The optional process may already be dead.
            }
        }
        if (connection != null) runCatching { context.unbindService(connection) }
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
            emergency = EmergencyProcessingGate(
                networkIdentified = details.hasProperty(
                    Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL,
                ),
                emergencyCallbackMode = isPotentialEmergencyCallback(details),
            ),
        )
        sessions[callId] = session
        announceCallPresence(session.callId, true)
        if (session.emergency.isProtected()) {
            applyEmergencyProtection(session)
        } else if (session.direction == Call.Details.DIRECTION_INCOMING) {
            requestIncomingDecision(session)
        } else {
            requestOutgoingProcessingDecision(session, ownerProcessingEnabled)
        }
    }

    fun onCallsChanged(values: List<CallUiState>) {
        check(Looper.myLooper() == Looper.getMainLooper())
        values.forEach { value ->
            val session = sessions[value.id] ?: return@forEach
            val becameEmergencyProtected = session.emergency.observeTelecom(
                networkIdentified = value.properties and
                    Call.Details.PROPERTY_NETWORK_IDENTIFIED_EMERGENCY_CALL != 0,
                emergencyCallbackMode = value.properties and
                    Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE != 0,
            )
            if (becameEmergencyProtected) {
                applyEmergencyProtection(session)
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
                service.onCallEnded(callId, disconnectCause)
            } catch (error: Exception) {
                if (error is RemoteException) invalidate(service)
                // Telephony has already ended; cleanup is best effort here.
            } finally {
                try {
                    service.setTelecomCallPresent(telecomLifecycleToken, callId, false)
                } catch (error: Exception) {
                    if (error is RemoteException) invalidate(service)
                    // Binder death also releases every call owned by this lifecycle token.
                }
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
        val lease = currentServiceLease()
        if (lease == null || !sessions.containsKey(callId)) {
            callbacks.onTakeOverResult(callId, false)
            return
        }
        val service = lease.service
        if (!pendingTakeovers.add(callId)) return
        worker.execute {
            val result = runCatching { service.takeOverCall(callId) }
            main.post {
                if (result.exceptionOrNull() is RemoteException) invalidate(lease)
                pendingTakeovers.remove(callId)
                sessions[callId]?.let { session ->
                    val succeeded = isCurrentLease(lease) && result.getOrDefault(false)
                    if (succeeded) session.answeredByAi = false
                    callbacks.onTakeOverResult(callId, succeeded)
                }
            }
        }
    }

    fun loadPolicy() {
        val lease = currentServiceLease()
        if (lease == null) {
            callbacks.onPolicyChanged(
                AssistantPolicyUiState(error = "Call-assistant service is unavailable"),
            )
            return
        }
        val service = lease.service
        callbacks.onPolicyChanged(AssistantPolicyUiState(loading = true))
        worker.execute {
            try {
                val policy = service.policy
                main.post {
                    if (isCurrentLease(lease)) {
                        ownerProcessingEnabled = policy.processingEnabled
                        callbacks.onPolicyChanged(policy.toUi())
                    }
                }
            } catch (error: Exception) {
                main.post {
                    if (isCurrentLease(lease)) {
                        if (error is RemoteException) invalidate(lease)
                        callbacks.onPolicyChanged(
                            AssistantPolicyUiState(error = "Could not read assistant settings"),
                        )
                    }
                }
            }
        }
    }

    fun savePolicy(value: AssistantPolicyUiState) {
        val lease = currentServiceLease()
        if (lease == null) {
            callbacks.onPolicyChanged(value.copy(
                available = false,
                saving = false,
                error = "Call-assistant service is unavailable",
            ))
            return
        }
        val service = lease.service
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
                    if (isCurrentLease(lease)) {
                        ownerProcessingEnabled = saved.processingEnabled
                        callbacks.onPolicyChanged(saved.toUi())
                    }
                }
            } catch (error: Exception) {
                main.post {
                    if (isCurrentLease(lease)) {
                        if (error is RemoteException) invalidate(lease)
                        callbacks.onPolicyChanged(value.copy(
                            available = true,
                            saving = false,
                            error = "Could not save assistant settings",
                        ))
                    }
                }
            }
        }
    }

    private fun requestIncomingDecision(session: Session) {
        val lease = currentServiceLease() ?: return
        val service = lease.service
        if (session.decisionRequested || session.state != Call.STATE_RINGING) return
        session.decisionRequested = true
        val numberCheck = session.emergency.beginNumberCheck()
        worker.execute {
            val knownContact = isKnownContact(session.address)
            val numberEmergency = isEmergency(session.address)
            session.emergency.completeNumberCheck(numberCheck, numberEmergency)
            val emergency = numberEmergency || session.emergency.isEmergencyCall()
            val emergencyCallbackMode = session.emergency.isEmergencyCallbackMode()
            val contextAddress = session.address.takeIf {
                ownerProcessingEnabled == true && !emergency && !emergencyCallbackMode
            }.orEmpty()
            val contextValue = IncomingCallContext().apply {
                callId = session.callId
                normalizedAddressHash = addressHash(session.address)
                transientAddress = contextAddress
                countryIso = if (contextAddress.isEmpty()) {
                    ""
                } else {
                    this@CallAssistantClient.countryIso()
                }
                this.knownContact = knownContact
                this.emergency = emergency
                this.emergencyCallbackMode = emergencyCallbackMode
                ringingSinceElapsedRealtimeMillis = SystemClock.elapsedRealtime()
            }
            try {
                val decision = service.evaluateIncoming(contextValue)
                main.post {
                    if (isCurrentLease(lease) && sessions[session.callId] === session) {
                        session.knownContact = knownContact
                        applyDecision(session.callId, decision)
                    }
                }
            } catch (error: Exception) {
                main.post {
                    if (isCurrentLease(lease) && sessions[session.callId] === session) {
                        session.decisionRequested = false
                        if (error is RemoteException) invalidate(lease)
                    }
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
            } catch (error: Exception) {
                if (error is RemoteException) invalidate(service)
            }
        }
    }

    private fun announceCallPresence(callId: String, present: Boolean) {
        val service = remote ?: return
        worker.execute {
            try {
                service.setTelecomCallPresent(telecomLifecycleToken, callId, present)
            } catch (error: Exception) {
                if (error is RemoteException) invalidate(service)
            }
        }
    }

    private fun applyDecision(callId: String, decision: CallHandlingDecision?) {
        val session = sessions[callId] ?: return
        if (session.emergency.isProtected()) {
            applyEmergencyProtection(session)
            return
        }
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

    private fun requestOutgoingProcessingDecision(
        session: Session,
        processingEnabled: Boolean?,
    ) {
        val numberCheck = session.emergency.beginNumberCheck()
        worker.execute {
            val numberEmergency = isEmergency(session.address)
            main.post {
                val current = sessions[session.callId]
                if (current !== session ||
                    !session.emergency.completeNumberCheck(numberCheck, numberEmergency)) {
                    return@post
                }
                if (session.emergency.isProtected()) {
                    applyEmergencyProtection(session)
                } else {
                    session.processingAllowed = processingEnabled
                    maybeNotifyAnswered(session)
                }
            }
        }
    }

    private fun applyEmergencyProtection(session: Session) {
        cancelDelayedAnswer(session)
        session.processingAllowed = false
        maybeNotifyAnswered(session)
        val service = remote ?: return
        worker.execute {
            try {
                service.onEmergencyCallDetected(session.callId)
            } catch (error: Exception) {
                if (error is RemoteException) invalidate(service)
                // Telephony remains authoritative even if optional AI cleanup fails.
            }
        }
    }

    private fun maybeNotifyAnswered(session: Session) {
        if (session.answeredNotified || session.state != Call.STATE_ACTIVE) return
        val processing = session.processingAllowed ?: return
        val service = remote ?: return
        session.answeredNotified = true
        worker.execute {
            try {
                service.onCallAnswered(session.callId, session.answeredByAi, processing)
            } catch (error: Exception) {
                main.post {
                    if (sessions[session.callId] === session && remote === service) {
                        session.answeredNotified = false
                        if (error is RemoteException) invalidate(service)
                    }
                }
            }
        }
    }

    private fun resumeActiveCall(session: Session, processingEnabled: Boolean) {
        if (session.answeredNotified || session.state != Call.STATE_ACTIVE) return
        val service = remote ?: return
        val processing = session.processingAllowed ?: processingEnabled
        session.processingAllowed = processing
        session.answeredNotified = true
        worker.execute {
            try {
                service.onCallResumed(
                    session.callId,
                    session.answeredByAi,
                    processing,
                    session.knownContact == true,
                )
            } catch (error: Exception) {
                main.post {
                    if (sessions[session.callId] === session && remote === service) {
                        session.answeredNotified = false
                        if (error is RemoteException) invalidate(service)
                    }
                }
            }
        }
    }

    private fun cancelDelayedAnswer(session: Session) {
        pendingAiAnswers.cancel(session.callId)
        session.delayedAnswer?.let(main::removeCallbacks)
        session.delayedAnswer = null
    }

    private fun handleServiceConnected(
        connection: AssistantServiceConnection,
        binder: IBinder,
    ) {
        main.post {
            if (!started || activeConnection !== connection ||
                connection.generation != connectionGeneration
            ) return@post
            armBindingWatchdog(connection)
            val service = IAiosCallIntelligence.Stub.asInterface(binder)
            if (service == null) {
                terminateBindingOnMain(connection, expected = null, immediate = false)
                return@post
            }
            remote = service
            connection.service = service
            worker.execute {
                try {
                    service.registerListener(connection.listener)
                    val policy = service.policy
                    val processing = policy.processingEnabled
                    main.post {
                        if (started && activeConnection === connection &&
                            remote === service
                        ) {
                            connectionReady = true
                            cancelBindingWatchdog()
                            rebindPolicy.connected()
                            ownerProcessingEnabled = processing
                            callbacks.onAssistantConnectionChanged(true)
                            callbacks.onPolicyChanged(policy.toUi())
                            announceEveryPresentCall(service)
                            sessions.values.forEach { session ->
                                if (session.emergency.isProtected()) {
                                    applyEmergencyProtection(session)
                                } else if (session.state == Call.STATE_ACTIVE) {
                                    resumeActiveCall(session, processing)
                                } else if (
                                    session.direction != Call.Details.DIRECTION_INCOMING
                                ) {
                                    requestOutgoingProcessingDecision(session, processing)
                                } else {
                                    requestIncomingDecision(session)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    main.post {
                        terminateBindingOnMain(
                            connection,
                            expected = service,
                            immediate = false,
                        )
                    }
                }
            }
        }
    }

    private fun handleServiceDisconnected(connection: AssistantServiceConnection) {
        main.post {
            if (!started || activeConnection !== connection ||
                connection.generation != connectionGeneration
            ) return@post
            clearRemoteState(expected = null)
            // Android retains ordinary disconnected bindings. The watchdog only
            // replaces it if the framework never reconnects the same generation.
            armBindingWatchdog(connection)
        }
    }

    private fun terminateBinding(
        connection: AssistantServiceConnection,
        immediate: Boolean,
    ) {
        main.post {
            terminateBindingOnMain(connection, expected = null, immediate = immediate)
        }
    }

    private fun terminateBindingOnMain(
        connection: AssistantServiceConnection,
        expected: IAiosCallIntelligence?,
        immediate: Boolean,
    ) {
        if (!started || activeConnection !== connection ||
            connection.generation != connectionGeneration ||
            (expected != null && remote !== expected)
        ) return
        cancelBindingWatchdog()
        clearRemoteState(expected = null)
        runCatching { context.unbindService(connection) }
        activeConnection = null
        scheduleRebind(immediate)
    }

    private fun invalidate(expected: IAiosCallIntelligence) {
        main.post {
            val connection = activeConnection ?: return@post
            terminateBindingOnMain(connection, expected, immediate = false)
        }
    }

    private fun invalidate(lease: ServiceLease) {
        main.post {
            if (!isCurrentLease(lease)) return@post
            terminateBindingOnMain(lease.connection, lease.service, immediate = false)
        }
    }

    private fun currentServiceLease(): ServiceLease? {
        val service = remote ?: return null
        val connection = activeConnection ?: return null
        return ServiceLease(service, connection).takeIf(::isCurrentLease)
    }

    private fun isCurrentLease(lease: ServiceLease): Boolean =
        remote === lease.service && isCurrentConnection(lease.connection)

    private fun isCurrentConnection(connection: AssistantServiceConnection): Boolean =
        started && activeConnection === connection &&
            connection.generation == connectionGeneration

    private fun isCurrentListener(connection: AssistantServiceConnection): Boolean =
        isCurrentConnection(connection) && connection.service != null &&
            remote === connection.service

    private fun clearRemoteState(expected: IAiosCallIntelligence?) {
        if (expected != null && remote !== expected) return
        remote = null
        connectionReady = false
        ownerProcessingEnabled = null
        sessions.values.forEach { session ->
            cancelDelayedAnswer(session)
            session.riskRevisions.nextGeneration()
            session.assistantRevisions.nextGeneration()
            if (session.state == Call.STATE_ACTIVE) session.answeredNotified = false
            if (session.state == Call.STATE_RINGING) {
                session.decisionRequested = false
                session.processingAllowed = null
            }
        }
        callbacks.onAssistantConnectionChanged(false)
    }

    private fun scheduleRebind(immediate: Boolean) {
        if (!started || activeConnection != null) return
        val delay = rebindPolicy.reserve(immediate)
        if (delay == PhoneServiceRebindPolicy.NO_RETRY) return
        lateinit var task: Runnable
        task = Runnable {
            if (rebindTask !== task) return@Runnable
            rebindTask = null
            if (rebindPolicy.begin()) bindNow()
        }
        rebindTask = task
        if (!main.postDelayed(task, delay)) {
            rebindTask = null
            rebindPolicy.close()
        }
    }

    private fun bindNow() {
        if (!started || activeConnection != null) return
        if (connectionGeneration == Long.MAX_VALUE) {
            callbacks.onAssistantConnectionChanged(false)
            return
        }
        val connection = AssistantServiceConnection(++connectionGeneration)
        activeConnection = connection
        connectionReady = false
        val intent = Intent(SERVICE_ACTION).setPackage(SERVICE_PACKAGE)
        val bound = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) {
            activeConnection = null
            callbacks.onAssistantConnectionChanged(false)
            scheduleRebind(immediate = false)
            return
        }
        armBindingWatchdog(connection)
    }

    private fun armBindingWatchdog(connection: AssistantServiceConnection) {
        cancelBindingWatchdog()
        lateinit var task: Runnable
        task = Runnable {
            if (bindingWatchdog !== task) return@Runnable
            bindingWatchdog = null
            if (started && activeConnection === connection && !connectionReady) {
                terminateBindingOnMain(connection, expected = null, immediate = false)
            }
        }
        bindingWatchdog = task
        if (!main.postDelayed(task, BINDING_WATCHDOG_MILLIS)) {
            bindingWatchdog = null
            terminateBindingOnMain(connection, expected = null, immediate = false)
        }
    }

    private fun cancelBindingWatchdog() {
        bindingWatchdog?.let(main::removeCallbacks)
        bindingWatchdog = null
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

    private fun countryIso(): String {
        val network = context.getSystemService(TelephonyManager::class.java)
            ?.networkCountryIso
            ?.takeIf(String::isNotBlank)
        return (network ?: Locale.getDefault().country).take(2)
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
        const val BINDING_WATCHDOG_MILLIS = 15_000L
    }
}
