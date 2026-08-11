package com.aios.callintelligence;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import com.aios.call.CallHandlingDecision;
import com.aios.call.CallAssistantPolicy;
import com.aios.call.CallAssistantState;
import com.aios.call.CallRiskAssessment;
import com.aios.call.IAiosCallIntelligence;
import com.aios.call.ICallIntelligenceListener;
import com.aios.call.IncomingCallContext;
import com.aios.call.TranscriptSegment;
import com.aios.model.GenerationChunk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CallIntelligenceService extends Service {
    private static final String PERMISSION_CONTROL =
            "com.aios.permission.CONTROL_CALL_INTELLIGENCE";
    private static final long DEFAULT_MISSED_DELAY_MILLIS = 15_000L;
    private static final int MAX_TELECOM_LIFECYCLE_TOKENS = 4;
    private static final int MAX_CALLS_PER_LIFECYCLE_TOKEN = 8;
    private static final int MAX_CONTEXT_CALLS = 64;

    private static final class PendingIncomingCall {
        final int ownerUid;
        final boolean knownContact;
        final boolean processingAllowed;

        PendingIncomingCall(int ownerUid, boolean knownContact, boolean processingAllowed) {
            this.ownerUid = ownerUid;
            this.knownContact = knownContact;
            this.processingAllowed = processingAllowed;
        }
    }

    private final RemoteCallbackList<ICallIntelligenceListener> listeners =
            new RemoteCallbackList<>();
    private final Object listenerBroadcastLock = new Object();
    private final Map<String, ActiveSession> sessions = new HashMap<>();
    private final Map<String, PendingIncomingCall> pendingIncomingCalls = new HashMap<>();
    private final Map<String, Integer> emergencyProtectedCalls = new HashMap<>();
    private final Map<String, CallCommunicationContextClient.PreparedContext>
            pendingCommunicationContexts = new HashMap<>();
    private final CallRequestIdentityTracker communicationContextRequests =
            new CallRequestIdentityTracker();
    private final Object telecomPresenceLock = new Object();
    private final TelecomCallPresenceTracker<IBinder> telecomPresence =
            new TelecomCallPresenceTracker<>(
                    MAX_TELECOM_LIFECYCLE_TOKENS, MAX_CALLS_PER_LIFECYCLE_TOKEN);
    private final Map<IBinder, IBinder.DeathRecipient> telecomPresenceDeaths =
            new HashMap<>();
    private final Runnable reconcileTelecomPresenceRunnable =
            this::reconcileTelecomPresence;
    private CallArtifactStore artifactStore;
    private AsrBrokerClient asr;
    private CallClassifierClient classifier;
    private ReceptionistDialogueClient receptionist;
    private CallerAudioUplink callerAudio;
    private SpeechSynthesisBrokerClient speech;
    private CallCommunicationContextClient communicationContext;
    private Handler mainHandler;
    private boolean appliedTelecomPresence;
    private boolean telecomPresenceUpdateScheduled;
    private boolean telecomPresenceStopping;
    private long nextSpeechRequestSerial;

    private final IAiosCallIntelligence.Stub binder = new IAiosCallIntelligence.Stub() {
        @Override
        public CallAssistantPolicy getPolicy() {
            enforceControlPermission();
            return readPolicy();
        }

        @Override
        public synchronized CallAssistantPolicy updatePolicy(CallAssistantPolicy requested) {
            enforceControlPermission();
            if (requested == null || !CallPolicyEngine.isKnownMode(requested.answerMode)
                    || !AnswerDelayPolicy.isKnownMode(requested.answerDelayMode)
                    || requested.missedDelayMillis < 3_000L
                    || requested.missedDelayMillis > 60_000L) {
                throw new IllegalArgumentException("invalid call-assistant policy");
            }
            SharedPreferences preferences = ownerPreferences();
            boolean callerHistoryWasEnabled = preferences.getBoolean(
                    "caller_history_enabled", false);
            SharedPreferences.Editor editor = preferences.edit()
                    .putString("answer_mode", requested.answerMode)
                    .putString("answer_delay_mode", requested.answerDelayMode)
                    .putLong("missed_delay_ms", requested.missedDelayMillis)
                    .putBoolean("processing_enabled", requested.processingEnabled)
                    .putBoolean("caller_history_enabled", requested.callerHistoryEnabled);
            if (!editor.commit()) {
                throw new IllegalStateException("call-assistant policy could not be saved");
            }
            if (callerHistoryWasEnabled && !requested.callerHistoryEnabled) {
                revokeCallerHistory();
            }
            return readPolicy();
        }

        @Override
        public CallHandlingDecision evaluateIncoming(IncomingCallContext context) {
            enforceControlPermission();
            int ownerUid = android.os.Binder.getCallingUid();
            if (context == null || context.callId == null || context.callId.isEmpty()
                    || context.callId.length() > 128) {
                return deniedDecision("invalid_call_id");
            }
            if (!ownsPresentTelecomCall(ownerUid, context.callId)) {
                return deniedDecision("telecom_call_not_registered");
            }
            synchronized (sessions) {
                Integer emergencyOwner = emergencyProtectedCalls.get(context.callId);
                if (emergencyOwner != null) {
                    return deniedDecision(emergencyOwner == ownerUid
                            ? "emergency_processing_blocked"
                            : "emergency_call_owned_by_another_uid");
                }
            }
            CallHandlingDecision decision = currentPolicy().evaluate(context);
            if (decision.aiMayAnswer && !AutomaticAnswerGate.mayAnswer(
                    decision.aiMayAnswer, callerInteractionTransportReady())) {
                decision = deniedAutomaticAnswerDecision(
                        decision.processingAllowed, automaticAnswerUnavailableReason());
            }
            boolean prepareContext = CallerHistoryPolicy.shouldPrepare(
                    ownerPreferences().getBoolean("caller_history_enabled", false),
                    context.emergency,
                    context.emergencyCallbackMode,
                    decision.processingAllowed,
                    context.transientAddress);
            Object contextRequestIdentity = prepareContext ? new Object() : null;
            synchronized (telecomPresenceLock) {
                if (telecomPresenceStopping
                        || !telecomPresence.ownsCall(ownerUid, context.callId)) {
                    return deniedDecision("telecom_call_not_registered");
                }
                synchronized (sessions) {
                    Integer emergencyOwner = emergencyProtectedCalls.get(context.callId);
                    if (emergencyOwner != null) {
                        return deniedDecision(emergencyOwner == ownerUid
                                ? "emergency_processing_blocked"
                                : "emergency_call_owned_by_another_uid");
                    }
                    if (pendingIncomingCalls.size() >= 64
                            && !pendingIncomingCalls.containsKey(context.callId)) {
                        pendingIncomingCalls.clear();
                    }
                    pendingIncomingCalls.put(
                            context.callId,
                            new PendingIncomingCall(
                                    ownerUid, context.knownContact, decision.processingAllowed));
                    if (prepareContext && !ownerPreferences().getBoolean(
                            "caller_history_enabled", false)) {
                        prepareContext = false;
                    }
                    if (prepareContext) {
                        if (!communicationContextRequests.tryStart(
                                context.callId,
                                contextRequestIdentity,
                                MAX_CONTEXT_CALLS)) {
                            prepareContext = false;
                        } else {
                            pendingCommunicationContexts.remove(context.callId);
                        }
                    }
                }
            }
            if (prepareContext && !communicationContext.prepareCall(
                    context.callId,
                    contextRequestIdentity,
                    context.transientAddress,
                    context.countryIso,
                    System.currentTimeMillis())) {
                synchronized (sessions) {
                    communicationContextRequests.finish(
                            context.callId, contextRequestIdentity);
                }
            }
            return decision;
        }

        @Override
        public void setTelecomCallPresent(
                IBinder lifecycleToken, String callId, boolean present) {
            enforceControlPermission();
            updateTelecomPresence(
                    android.os.Binder.getCallingUid(), lifecycleToken, callId, present);
        }

        @Override
        public void onCallAnswered(
                String callId, boolean answeredByAi, boolean processingAllowed) {
            handleConnectedCall(
                    callId, answeredByAi, processingAllowed, false, false);
        }

        @Override
        public void onCallResumed(
                String callId,
                boolean aiHandling,
                boolean processingAllowed,
                boolean knownContact) {
            handleConnectedCall(
                    callId, aiHandling, processingAllowed, true, knownContact);
        }

        @Override
        public void onEmergencyCallDetected(String callId) {
            enforceControlPermission();
            if (callId == null || callId.isEmpty() || callId.length() > 128) {
                notifyStatus(callId, 0, "invalid_call_id");
                return;
            }
            int ownerUid = android.os.Binder.getCallingUid();
            boolean authorized = ownsPresentTelecomCall(ownerUid, callId);
            ActiveSession stopped = null;
            String rejection = null;
            synchronized (sessions) {
                Integer existingEmergencyOwner = emergencyProtectedCalls.get(callId);
                ActiveSession candidate = sessions.get(callId);
                if (existingEmergencyOwner != null && existingEmergencyOwner != ownerUid) {
                    rejection = "emergency_call_owned_by_another_uid";
                } else if (candidate != null && !candidate.ownedBy(ownerUid)) {
                    rejection = "call_session_owned_by_another_uid";
                } else {
                    PendingIncomingCall pending = pendingIncomingCalls.get(callId);
                    if (candidate != null || (pending != null && pending.ownerUid == ownerUid)) {
                        authorized = true;
                    }
                    if (!authorized) {
                        rejection = "telecom_call_not_owned_or_active";
                    } else {
                        emergencyProtectedCalls.put(callId, ownerUid);
                        pendingIncomingCalls.remove(callId);
                        communicationContextRequests.remove(callId);
                        pendingCommunicationContexts.remove(callId);
                        stopped = sessions.remove(callId);
                    }
                }
            }
            if (rejection != null) {
                notifyStatus(callId, -8, rejection);
                return;
            }
            classifier.endCall(callId);
            receptionist.endCall(callId);
            if (stopped != null) {
                ActiveSession.TakeoverResult takeover = stopped.takeOver();
                if (takeover != null) {
                    takeover.closeAudio();
                    publishAssistantState(callId, stopped, takeover.update);
                }
                stopped.close();
            }
            communicationContext.discardCall(callId);
            try {
                artifactStore.discard(callId);
            } catch (IOException error) {
                notifyStatus(callId, -9, "emergency_artifact_deletion_failed");
            }
            long now = System.currentTimeMillis();
            artifactStore.cleanup(now);
            RetentionAlarm.scheduleNext(CallIntelligenceService.this, artifactStore);
            notifyStatus(callId, 10, "emergency_processing_stopped");
        }

        @Override
        public boolean takeOverCall(String callId) {
            enforceControlPermission();
            if (callId == null || callId.isEmpty() || callId.length() > 128) return false;
            int ownerUid = android.os.Binder.getCallingUid();
            ActiveSession session;
            ActiveSession.TakeoverResult takeover;
            synchronized (sessions) {
                session = sessions.get(callId);
                takeover = session == null || !session.ownedBy(ownerUid)
                        ? null : session.takeOver();
            }
            if (takeover == null) return false;
            takeover.closeAudio();
            receptionist.endCall(callId);
            classifier.beginCall(callId, takeover.knownContact);
            synchronized (sessions) {
                if (sessions.get(callId) != session || !session.isOpen()) {
                    classifier.endCall(callId);
                    return false;
                }
            }
            publishAssistantState(callId, session, takeover.update);
            notifyStatus(callId, 8, "owner_takeover_complete");
            return true;
        }

        @Override
        public void onCallEnded(String callId, int disconnectCause) {
            enforceControlPermission();
            if (callId == null || callId.isEmpty() || callId.length() > 128) {
                notifyStatus(callId, 0, "invalid_call_id");
                return;
            }
            int ownerUid = android.os.Binder.getCallingUid();
            boolean ownsPresentCall = ownsPresentTelecomCall(ownerUid, callId);
            ActiveSession ended = null;
            CallCommunicationContextClient.PreparedContext pendingContext = null;
            String rejection = null;
            boolean authorized = ownsPresentCall;
            synchronized (sessions) {
                Integer emergencyOwner = emergencyProtectedCalls.get(callId);
                ActiveSession candidate = sessions.get(callId);
                if (emergencyOwner != null && emergencyOwner != ownerUid) {
                    rejection = "emergency_call_owned_by_another_uid";
                } else if (candidate != null && !candidate.ownedBy(ownerUid)) {
                    rejection = "call_session_owned_by_another_uid";
                } else if (candidate != null) {
                    authorized = true;
                }
                if (rejection == null) {
                    if (emergencyOwner != null) authorized = true;
                    PendingIncomingCall pending = pendingIncomingCalls.get(callId);
                    if (pending != null && pending.ownerUid == ownerUid) {
                        pendingIncomingCalls.remove(callId);
                        authorized = true;
                    }
                    if (!authorized) {
                        rejection = "telecom_call_not_owned_or_active";
                    }
                }
                if (rejection == null) {
                    emergencyProtectedCalls.remove(callId, ownerUid);
                    communicationContextRequests.remove(callId);
                    pendingContext = pendingCommunicationContexts.remove(callId);
                    ended = sessions.remove(callId);
                    if (ended != null && pendingContext != null) {
                        ended.setCommunicationContext(pendingContext);
                    }
                }
            }
            if (rejection != null) {
                notifyStatus(callId, -8, rejection);
                return;
            }
            classifier.endCall(callId);
            receptionist.endCall(callId);
            long now = System.currentTimeMillis();
            ActiveSession.ContextRecord contextRecord = ended == null
                    ? null : ended.contextRecord(disconnectCause, now);
            if (ended != null) ended.close();
            if (contextRecord != null) {
                communicationContext.indexCallArtifact(
                        callId,
                        contextRecord.prepared,
                        contextRecord.sourceId,
                        contextRecord.revision,
                        contextRecord.eventAtEpochMillis,
                        contextRecord.expiresAtEpochMillis,
                        contextRecord.text,
                        now);
            } else {
                communicationContext.discardCall(callId);
            }
            artifactStore.cleanup(now);
            RetentionAlarm.scheduleNext(CallIntelligenceService.this, artifactStore);
            notifyStatus(callId, 2, "call_ended");
        }

        @Override
        public void registerListener(ICallIntelligenceListener listener) {
            enforceControlPermission();
            if (listener != null && listeners.register(listener)) {
                List<CallRiskAssessment> latestRisks = new ArrayList<>();
                List<CallAssistantState> latestAssistantStates = new ArrayList<>();
                synchronized (sessions) {
                    for (Map.Entry<String, ActiveSession> entry : sessions.entrySet()) {
                        RiskAssessmentTracker.Update riskUpdate =
                                entry.getValue().currentRiskUpdate();
                        if (riskUpdate != null) {
                            latestRisks.add(toRiskAssessment(entry.getKey(), riskUpdate));
                        }
                        AssistantHandlingTracker.Update assistantUpdate =
                                entry.getValue().currentAssistantState();
                        if (assistantUpdate != null) {
                            latestAssistantStates.add(
                                    toAssistantState(entry.getKey(), assistantUpdate));
                        }
                    }
                }
                for (CallRiskAssessment assessment : latestRisks) {
                    try {
                        listener.onRiskChanged(assessment);
                    } catch (Exception ignored) {
                        break;
                    }
                }
                for (CallAssistantState state : latestAssistantStates) {
                    try {
                        listener.onAssistantStateChanged(state);
                    } catch (Exception ignored) {
                        break;
                    }
                }
            }
        }

        @Override
        public void unregisterListener(ICallIntelligenceListener listener) {
            enforceControlPermission();
            if (listener != null) {
                listeners.unregister(listener);
            }
        }
    };

    private void handleConnectedCall(
            String callId,
            boolean answeredByAi,
            boolean processingAllowed,
            boolean resumedAfterServiceLoss,
            boolean resumedKnownContact) {
        enforceControlPermission();
        int ownerUid = android.os.Binder.getCallingUid();
        if (callId == null || callId.isEmpty() || callId.length() > 128) {
            notifyStatus(callId, 0, "invalid_call_id");
            return;
        }
        if (!ownsPresentTelecomCall(ownerUid, callId)) {
            notifyStatus(callId, -8, "telecom_call_not_owned_or_active");
            return;
        }
        PendingIncomingCall pending;
        Integer emergencyOwner;
        synchronized (sessions) {
            emergencyOwner = emergencyProtectedCalls.get(callId);
            pending = pendingIncomingCalls.get(callId);
            if (pending != null && pending.ownerUid == ownerUid) {
                pendingIncomingCalls.remove(callId);
            }
        }
        if (emergencyOwner != null) {
            if (emergencyOwner != ownerUid) {
                notifyStatus(callId, -8, "emergency_call_owned_by_another_uid");
            } else {
                notifyStatus(callId, 0, "emergency_processing_blocked");
            }
            return;
        }
        if (pending != null && pending.ownerUid != ownerUid) {
            notifyStatus(callId, -8, "call_admission_owned_by_another_uid");
            return;
        }
        boolean admittedProcessing = pending == null || pending.processingAllowed;
        boolean ownerProcessingEnabled = ownerPreferences().getBoolean(
                "processing_enabled", false);
        if (!processingAllowed || !admittedProcessing || !ownerProcessingEnabled) {
            notifyStatus(callId, 0, "processing_not_allowed");
            return;
        }
        boolean knownContact = pending != null
                ? pending.knownContact
                : resumedAfterServiceLoss && resumedKnownContact;

        if (!answeredByAi) {
            beginCapture(
                    callId,
                    ownerUid,
                    false,
                    knownContact,
                    resumedAfterServiceLoss);
            return;
        }

        if (!callerInteractionTransportReady()) {
            notifyStatus(callId, -4, automaticAnswerUnavailableReason());
            return;
        }
        beginCapture(
                callId,
                ownerUid,
                true,
                knownContact,
                resumedAfterServiceLoss);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        artifactStore = new CallArtifactStore(this);
        artifactStore.cleanup(System.currentTimeMillis());
        RetentionAlarm.scheduleNext(this, artifactStore);
        communicationContext = new CallCommunicationContextClient(
                this,
                new CallCommunicationContextClient.Listener() {
                    @Override
                    public void onContextReady(
                            String callId,
                            Object requestIdentity,
                            CallCommunicationContextClient.PreparedContext context) {
                        handleCommunicationContext(callId, requestIdentity, context);
                    }

                    @Override
                    public void onStatus(String callId, String detail) {
                        notifyStatus(callId, 9, detail);
                    }
                });
        communicationContext.start();
        asr = new AsrBrokerClient(this, new AsrBrokerClient.Listener() {
            @Override
            public void onTranscript(
                    String callId,
                    String direction,
                    Object streamIdentity,
                    String language,
                    GenerationChunk chunk) {
                handleTranscript(callId, direction, streamIdentity, language, chunk);
            }

            @Override
            public void onAsrStatus(
                    String callId,
                    String direction,
                    Object streamIdentity,
                    String detail) {
                handleAsrStatus(callId, direction, streamIdentity, detail);
            }

            @Override
            public void onAsrReady(Object brokerIdentity) {
                restoreLiveAsrStreams(brokerIdentity);
            }

            @Override
            public void onAsrUnavailable(Object brokerIdentity) {
                detachLostAsrStreams(brokerIdentity);
            }
        });
        asr.start();
        classifier = new CallClassifierClient(this, new CallClassifierClient.Listener() {
            @Override
            public void onModelAssessment(
                    String callId, CallClassifierClient.ModelAssessment assessment) {
                handleModelAssessment(callId, assessment);
            }

            @Override
            public void onClassifierStatus(String callId, String detail) {
                notifyStatus(callId, 4, detail);
            }
        });
        classifier.start();
        receptionist = new ReceptionistDialogueClient(
                this,
                new ReceptionistDialogueClient.Listener() {
                    @Override
                    public void onReply(
                            String callId, ReceptionistDialogueClient.Reply reply) {
                        handleReceptionistReply(callId, reply);
                    }

                    @Override
                    public void onStatus(String callId, String detail) {
                        handleReceptionistStatus(callId, detail);
                    }
                });
        receptionist.start();
        callerAudio = new CallerAudioUplink(this);
        speech = new SpeechSynthesisBrokerClient(
                this,
                (requestId, detail) -> notifyStatus(requestId, 5, detail));
        speech.start();
    }

    @Override
    public IBinder onBind(Intent intent) {
        enforceControlPermission();
        return binder;
    }

    @Override
    public void onDestroy() {
        stopTelecomPresenceTracking();
        synchronized (sessions) {
            pendingIncomingCalls.clear();
            emergencyProtectedCalls.clear();
            pendingCommunicationContexts.clear();
            communicationContextRequests.clear();
            for (ActiveSession session : sessions.values()) {
                session.close();
            }
            sessions.clear();
        }
        if (speech != null) speech.close();
        if (callerAudio != null) callerAudio.close();
        if (receptionist != null) receptionist.close();
        if (communicationContext != null) communicationContext.close();
        listeners.kill();
        if (asr != null) asr.close();
        if (classifier != null) classifier.close();
        super.onDestroy();
    }

    private CallPolicyEngine currentPolicy() {
        SharedPreferences preferences = ownerPreferences();
        return new CallPolicyEngine(
                preferences.getString("answer_mode", CallPolicyEngine.MODE_OFF),
                preferences.getLong("missed_delay_ms", DEFAULT_MISSED_DELAY_MILLIS),
                preferences.getString("answer_delay_mode", AnswerDelayPolicy.DEFAULT_MODE),
                preferences.getBoolean("processing_enabled", false));
    }

    private static CallHandlingDecision deniedDecision(String reason) {
        CallHandlingDecision value = new CallHandlingDecision();
        value.action = CallHandlingDecision.ACTION_BYPASS_AI;
        value.answerDelayMillis = 0L;
        value.aiMayAnswer = false;
        value.processingAllowed = false;
        value.reason = reason;
        return value;
    }

    private static CallHandlingDecision deniedAutomaticAnswerDecision(
            boolean processingAllowed, String reason) {
        CallHandlingDecision value = new CallHandlingDecision();
        value.action = CallHandlingDecision.ACTION_RING_OWNER;
        value.answerDelayMillis = 0L;
        value.aiMayAnswer = false;
        value.processingAllowed = processingAllowed;
        value.reason = reason;
        return value;
    }

    private SharedPreferences ownerPreferences() {
        return getSharedPreferences("owner_policy", MODE_PRIVATE);
    }

    private CallAssistantPolicy readPolicy() {
        SharedPreferences preferences = ownerPreferences();
        CallAssistantPolicy value = new CallAssistantPolicy();
        value.answerMode = preferences.getString(
                "answer_mode", CallPolicyEngine.MODE_OFF);
        value.answerDelayMode = preferences.getString(
                "answer_delay_mode", AnswerDelayPolicy.DEFAULT_MODE);
        value.missedDelayMillis = CallPolicyEngine.clampDelay(
                preferences.getLong("missed_delay_ms", DEFAULT_MISSED_DELAY_MILLIS));
        value.processingEnabled = preferences.getBoolean("processing_enabled", false);
        value.callerHistoryEnabled = preferences.getBoolean(
                "caller_history_enabled", false);
        value.automaticAnswerAvailable = callerInteractionTransportReady();
        value.automaticAnswerUnavailableReason = value.automaticAnswerAvailable
                ? "" : automaticAnswerUnavailableReason();
        return value;
    }

    private boolean callerInteractionTransportReady() {
        return CallProductProperties.callerUplinkValidated()
                && asr != null
                && asr.isAvailable()
                && callerAudio != null
                && callerAudio.probe().available
                && speech != null
                && speech.isAvailable("en")
                && speech.isAvailable("es")
                && receptionist != null
                && receptionist.isAvailable("en")
                && receptionist.isAvailable("es");
    }

    private String automaticAnswerUnavailableReason() {
        if (!CallProductProperties.callerUplinkValidated()) {
            return "caller_audio_injection_requires_physical_validation";
        }
        if (asr == null || !asr.isAvailable()) {
            return "streaming_asr_unavailable";
        }
        if (callerAudio == null) {
            return "caller_audio_uplink_unavailable";
        }
        CallerAudioUplink.Probe probe = callerAudio.probe();
        if (!probe.available) {
            return probe.reason;
        }
        if (speech == null || !speech.isAvailable("en") || !speech.isAvailable("es")) {
            return "speech_synthesis_languages_unavailable";
        }
        if (receptionist == null || !receptionist.isAvailable("en")
                || !receptionist.isAvailable("es")) {
            return "receptionist_languages_unavailable";
        }
        return "caller_interaction_transport_unavailable";
    }

    private void beginCapture(
            String callId,
            int ownerUid,
            boolean answeredByAi,
            boolean knownContact,
            boolean resumedAfterServiceLoss) {
        ActiveSession started;
        synchronized (telecomPresenceLock) {
            if (telecomPresenceStopping || !telecomPresence.ownsCall(ownerUid, callId)) {
                notifyStatus(callId, -8, "telecom_call_not_owned_or_active");
                return;
            }
            synchronized (sessions) {
                Integer emergencyOwner = emergencyProtectedCalls.get(callId);
                if (emergencyOwner != null) {
                    notifyStatus(callId, emergencyOwner == ownerUid ? 0 : -8,
                            emergencyOwner == ownerUid
                                    ? "emergency_processing_blocked"
                                    : "emergency_call_owned_by_another_uid");
                    return;
                }
                started = beginCaptureLocked(callId, ownerUid, answeredByAi, knownContact);
            }
        }
        if (started != null) {
            publishAssessment(callId, started, started.initialAssessment());
            publishAssistantState(callId, started, started.initialAssistantState());
        }
        if (started != null
                && AssistantGreetingPolicy.shouldGreet(
                        answeredByAi, resumedAfterServiceLoss)
                && started.beginGreeting()) {
            String language = "es".equals(Locale.getDefault().getLanguage()) ? "es" : "en";
            String greeting = "es".equals(language)
                    ? "Hola, ¿cómo puedo ayudarle?"
                    : "Hello, how can I help you?";
            speakToCaller(callId, started, language, greeting);
        }
    }

    private ActiveSession beginCaptureLocked(
            String callId, int ownerUid, boolean answeredByAi, boolean knownContact) {
        if (sessions.containsKey(callId)) {
            notifyStatus(callId, 1, "capture_already_started");
            return null;
        }
        CallArtifactStore.Session stored = null;
        AsrBrokerClient.Stream downlinkAsr = null;
        AsrBrokerClient.Stream uplinkAsr = null;
        ResilientFanoutOutputStream downlinkFanout = null;
        ResilientFanoutOutputStream uplinkFanout = null;
        TelephonyAudioCapture capture = null;
        try {
            CallCommunicationContextClient.PreparedContext preparedContext =
                    pendingCommunicationContexts.remove(callId);
            stored = artifactStore.create(callId, answeredByAi, System.currentTimeMillis());
            downlinkAsr = asr.openStream(callId, "downlink");
            uplinkAsr = asr.openStream(callId, "uplink");
            if (answeredByAi && downlinkAsr == null) {
                throw new IOException("incoming ASR is required for AI answering");
            }
            downlinkFanout = new ResilientFanoutOutputStream(
                    stored.openDownlink(), sink(downlinkAsr));
            uplinkFanout = new ResilientFanoutOutputStream(
                    stored.openUplink(), sink(uplinkAsr));
            capture = new TelephonyAudioCapture(this, downlinkFanout, uplinkFanout);
            ActiveSession active = new ActiveSession(
                    stored, capture, downlinkFanout, uplinkFanout,
                    downlinkAsr, uplinkAsr,
                    new SpamRiskEngine(knownContact), ownerUid, answeredByAi, knownContact,
                    preparedContext);
            sessions.put(callId, active);
            if (answeredByAi) {
                receptionist.beginCall(
                        callId,
                        knownContact,
                        preparedContext == null ? "[]" : preparedContext.priorContextJson);
            } else {
                classifier.beginCall(callId, knownContact);
            }
            capture.startRequired();
            RetentionAlarm.scheduleNext(this, artifactStore);
            notifyStatus(callId, 1, "capture_started");
            return active;
        } catch (IOException | RuntimeException error) {
            sessions.remove(callId);
            if (answeredByAi) {
                receptionist.endCall(callId);
            } else {
                classifier.endCall(callId);
            }
            if (capture != null) capture.close();
            if (capture == null) {
                closeQuietly(downlinkFanout);
                closeQuietly(uplinkFanout);
            }
            if (downlinkAsr != null) downlinkAsr.close();
            if (uplinkAsr != null) uplinkAsr.close();
            if (stored != null) stored.close();
            notifyStatus(callId, -1, "capture_unavailable");
            return null;
        }
    }

    private void enforceControlPermission() {
        enforceCallingOrSelfPermission(PERMISSION_CONTROL, "unauthorized dialer caller");
    }

    private boolean ownsPresentTelecomCall(int ownerUid, String callId) {
        synchronized (telecomPresenceLock) {
            return !telecomPresenceStopping && telecomPresence.ownsCall(ownerUid, callId);
        }
    }

    private void updateTelecomPresence(
            int ownerUid, IBinder token, String callId, boolean present) {
        if (token == null || callId == null || callId.isEmpty() || callId.length() > 128) {
            throw new IllegalArgumentException("valid Telecom token and opaque call ID required");
        }
        IBinder.DeathRecipient removedRecipient = null;
        boolean releasedOrphanedWork = false;
        synchronized (telecomPresenceLock) {
            if (telecomPresenceStopping) {
                throw new IllegalStateException("call intelligence is stopping");
            }
            Integer existingOwner = telecomPresence.ownerUid(token);
            if (present && existingOwner == null) {
                IBinder.DeathRecipient recipient = () -> onTelecomPresenceTokenDied(token);
                try {
                    token.linkToDeath(recipient, 0);
                } catch (RemoteException error) {
                    throw new IllegalArgumentException(
                            "Telecom lifecycle token is already dead", error);
                }
                try {
                    telecomPresence.setPresent(token, ownerUid, callId, true);
                    telecomPresenceDeaths.put(token, recipient);
                } catch (RuntimeException error) {
                    token.unlinkToDeath(recipient, 0);
                    throw error;
                }
            } else {
                if (present) {
                    telecomPresence.setPresent(token, ownerUid, callId, true);
                } else {
                    TelecomCallPresenceTracker.Release release =
                            telecomPresence.releaseAndReport(token, ownerUid, callId);
                    if (release.callOrphaned) {
                        synchronized (sessions) {
                            releasedOrphanedWork =
                                    stopOrphanedWorkLocked(callId, ownerUid);
                        }
                    }
                }
                if (!present && telecomPresence.ownerUid(token) == null) {
                    removedRecipient = telecomPresenceDeaths.remove(token);
                }
            }
            scheduleTelecomPresenceReconciliationLocked();
        }
        if (removedRecipient != null) {
            token.unlinkToDeath(removedRecipient, 0);
        }
        if (releasedOrphanedWork) {
            finishOrphanedCallCleanup(
                    List.of(callId), "telecom_presence_released");
        }
    }

    private void onTelecomPresenceTokenDied(IBinder token) {
        List<String> orphanedCallIds = new ArrayList<>();
        synchronized (telecomPresenceLock) {
            if (telecomPresenceStopping) {
                return;
            }
            telecomPresenceDeaths.remove(token);
            TelecomCallPresenceTracker.DeadClient dead =
                    telecomPresence.removeDeadAndReport(token);
            if (dead.ownerUid != null && !dead.orphanedCallIds.isEmpty()) {
                synchronized (sessions) {
                    for (String callId : dead.orphanedCallIds) {
                        stopOrphanedWorkLocked(callId, dead.ownerUid);
                        orphanedCallIds.add(callId);
                    }
                }
            }
            scheduleTelecomPresenceReconciliationLocked();
        }
        finishOrphanedCallCleanup(orphanedCallIds, "dialer_process_died");
    }

    /** Called with {@link #sessions} held and before Telecom presence can be reclaimed. */
    private boolean stopOrphanedWorkLocked(String callId, int ownerUid) {
        Integer emergencyOwner = emergencyProtectedCalls.get(callId);
        PendingIncomingCall pending = pendingIncomingCalls.get(callId);
        ActiveSession active = sessions.get(callId);
        if ((emergencyOwner != null && emergencyOwner != ownerUid)
                || (pending != null && pending.ownerUid != ownerUid)
                || (active != null && !active.ownedBy(ownerUid))) {
            return false;
        }
        boolean hadWork = emergencyOwner != null
                || pending != null
                || communicationContextRequests.contains(callId)
                || pendingCommunicationContexts.containsKey(callId)
                || active != null;
        if (!hadWork) return false;
        emergencyProtectedCalls.remove(callId, ownerUid);
        pendingIncomingCalls.remove(callId);
        communicationContextRequests.remove(callId);
        pendingCommunicationContexts.remove(callId);
        sessions.remove(callId);
        if (active != null) active.close();
        classifier.endCall(callId);
        receptionist.endCall(callId);
        communicationContext.discardCall(callId);
        return true;
    }

    private void finishOrphanedCallCleanup(List<String> orphanedCallIds, String detail) {
        if (orphanedCallIds.isEmpty()) return;
        for (String callId : orphanedCallIds) {
            notifyStatus(callId, -10, detail);
        }
        long now = System.currentTimeMillis();
        artifactStore.cleanup(now);
        RetentionAlarm.scheduleNext(this, artifactStore);
    }

    private void scheduleTelecomPresenceReconciliationLocked() {
        if (appliedTelecomPresence == telecomPresence.isActive()
                || telecomPresenceUpdateScheduled) {
            return;
        }
        telecomPresenceUpdateScheduled = true;
        if (!mainHandler.post(reconcileTelecomPresenceRunnable)) {
            telecomPresenceUpdateScheduled = false;
        }
    }

    private void reconcileTelecomPresence() {
        while (true) {
            final boolean desired;
            synchronized (telecomPresenceLock) {
                if (telecomPresenceStopping) {
                    telecomPresenceUpdateScheduled = false;
                    return;
                }
                desired = telecomPresence.isActive();
                if (appliedTelecomPresence == desired) {
                    telecomPresenceUpdateScheduled = false;
                    return;
                }
            }
            asr.setCallActive(desired);
            synchronized (telecomPresenceLock) {
                appliedTelecomPresence = desired;
            }
        }
    }

    private void stopTelecomPresenceTracking() {
        List<Map.Entry<IBinder, IBinder.DeathRecipient>> deaths;
        synchronized (telecomPresenceLock) {
            telecomPresenceStopping = true;
            if (mainHandler != null) {
                mainHandler.removeCallbacks(reconcileTelecomPresenceRunnable);
            }
            deaths = new ArrayList<>(telecomPresenceDeaths.entrySet());
            telecomPresenceDeaths.clear();
            telecomPresence.clear();
            telecomPresenceUpdateScheduled = false;
        }
        for (Map.Entry<IBinder, IBinder.DeathRecipient> death : deaths) {
            death.getKey().unlinkToDeath(death.getValue(), 0);
        }
    }

    private void notifyStatus(String callId, int status, String detail) {
        synchronized (listenerBroadcastLock) {
            int count = listeners.beginBroadcast();
            try {
                for (int index = 0; index < count; index++) {
                    try {
                        listeners.getBroadcastItem(index).onServiceStatus(
                                callId, status, detail);
                    } catch (Exception ignored) {
                        // RemoteCallbackList removes dead clients.
                    }
                }
            } finally {
                listeners.finishBroadcast();
            }
        }
    }

    private void notifyRisk(CallRiskAssessment assessment) {
        synchronized (listenerBroadcastLock) {
            int count = listeners.beginBroadcast();
            try {
                for (int index = 0; index < count; index++) {
                    try {
                        listeners.getBroadcastItem(index).onRiskChanged(assessment);
                    } catch (Exception ignored) {
                        // Dead listeners are removed by RemoteCallbackList.
                    }
                }
            } finally {
                listeners.finishBroadcast();
            }
        }
    }

    private void notifyAssistantState(CallAssistantState state) {
        synchronized (listenerBroadcastLock) {
            int count = listeners.beginBroadcast();
            try {
                for (int index = 0; index < count; index++) {
                    try {
                        listeners.getBroadcastItem(index).onAssistantStateChanged(state);
                    } catch (Exception ignored) {
                        // Dead listeners are removed by RemoteCallbackList.
                    }
                }
            } finally {
                listeners.finishBroadcast();
            }
        }
    }

    private void notifyTranscript(TranscriptSegment segment) {
        synchronized (listenerBroadcastLock) {
            int count = listeners.beginBroadcast();
            try {
                for (int index = 0; index < count; index++) {
                    try {
                        listeners.getBroadcastItem(index).onTranscript(segment);
                    } catch (Exception ignored) {
                        // Dead listeners are removed by RemoteCallbackList.
                    }
                }
            } finally {
                listeners.finishBroadcast();
            }
        }
    }

    private void handleCommunicationContext(
            String callId,
            Object requestIdentity,
            CallCommunicationContextClient.PreparedContext prepared) {
        if (callId == null || requestIdentity == null
                || prepared == null || prepared.identity == null) return;
        boolean historyEnabled = ownerPreferences().getBoolean(
                "caller_history_enabled", false);
        ActiveSession session;
        synchronized (sessions) {
            if (!communicationContextRequests.isCurrent(callId, requestIdentity)) return;
            if (!historyEnabled) {
                communicationContextRequests.finish(callId, requestIdentity);
                pendingCommunicationContexts.remove(callId);
                communicationContext.discardCall(callId);
                return;
            }
            session = sessions.get(callId);
            if (session == null) {
                pendingCommunicationContexts.put(callId, prepared);
            } else {
                session.setCommunicationContext(prepared);
            }
        }
        if (session != null && session.isAiHandling()) {
            receptionist.updatePriorContext(callId, prepared.priorContextJson);
        }
        notifyStatus(callId, 9, "communication_context_ready");
    }

    private void revokeCallerHistory() {
        List<String> callIds;
        synchronized (sessions) {
            callIds = communicationContextRequests.callIds();
            communicationContextRequests.clear();
            pendingCommunicationContexts.clear();
            for (Map.Entry<String, ActiveSession> entry : sessions.entrySet()) {
                if (entry.getValue().clearCommunicationContext()
                        && !callIds.contains(entry.getKey())) {
                    callIds.add(entry.getKey());
                }
            }
        }
        for (String callId : callIds) {
            if (communicationContext != null) communicationContext.discardCall(callId);
            if (receptionist != null) receptionist.updatePriorContext(callId, "[]");
        }
    }

    private void handleTranscript(
            String callId,
            String direction,
            Object streamIdentity,
            String language,
            GenerationChunk chunk) {
        ActiveSession session;
        synchronized (sessions) {
            session = sessions.get(callId);
        }
        if (!asr.acceptsCallback(streamIdentity)
                || session == null
                || !session.acceptsAsrCallback(direction, streamIdentity)) {
            return;
        }
        try {
            session.stored.appendTranscript(
                    direction,
                    language,
                    chunk.text,
                    chunk.isFinal,
                    chunk.confidence,
                    chunk.sourceStartMillis,
                    chunk.sourceEndMillis);
        } catch (IOException error) {
            notifyStatus(callId, -2, "transcript_storage_failed");
        }
        session.appendContextTranscript(
                direction, language, chunk.text, chunk.isFinal);
        TranscriptSegment segment = new TranscriptSegment();
        segment.callId = callId;
        segment.direction = direction;
        segment.language = language;
        segment.text = chunk.text;
        segment.isFinal = chunk.isFinal;
        segment.confidence = chunk.confidence;
        segment.startMillis = chunk.sourceStartMillis;
        segment.endMillis = chunk.sourceEndMillis;
        notifyTranscript(segment);
        if ("downlink".equals(direction)
                && chunk.text != null && !chunk.text.isBlank()) {
            // Whisper partials replace the current turn rather than append to it.
            // The heuristic is set-based, so observing each revision builds risk
            // context early. Provisional evidence is replaced by the next partial;
            // only the final turn makes its signals durable.
            RiskAssessmentTracker.Update assessment =
                    session.observeHeuristicRevision(
                            chunk.text, language, chunk.isFinal, chunk.sequence);
            publishAssessment(callId, session, assessment);
            if (!session.isAiHandling()) {
                classifier.observeRevision(
                        callId,
                        language,
                        chunk.text,
                        chunk.isFinal,
                        chunk.sequence);
            }
            if (chunk.isFinal) {
                if (session.isAiHandling()) {
                    requestReceptionistReply(callId, session, language, chunk.text);
                }
            }
        }
    }

    private void restoreLiveAsrStreams(Object brokerIdentity) {
        if (brokerIdentity == null) return;
        List<Map.Entry<String, ActiveSession>> snapshot;
        synchronized (sessions) {
            snapshot = new ArrayList<>(sessions.entrySet());
        }
        for (Map.Entry<String, ActiveSession> item : snapshot) {
            String callId = item.getKey();
            ActiveSession session = item.getValue();
            if (!session.needsAsrRestore(brokerIdentity)) continue;
            AsrBrokerClient.Stream downlink = asr.openStream(callId, "downlink");
            AsrBrokerClient.Stream uplink = asr.openStream(callId, "uplink");
            if (downlink == null) {
                if (uplink != null) uplink.close();
                notifyStatus(callId, -3, "incoming_asr_restore_unavailable");
                continue;
            }
            if (!session.replaceAsrStreams(brokerIdentity, downlink, uplink)) {
                downlink.close();
                if (uplink != null) uplink.close();
                continue;
            }
            notifyStatus(callId, 3, uplink == null
                    ? "incoming_asr_restored_uplink_unavailable"
                    : "asr_streams_restored");
        }
    }

    private void detachLostAsrStreams(Object brokerIdentity) {
        if (brokerIdentity == null) return;
        List<Map.Entry<String, ActiveSession>> snapshot;
        synchronized (sessions) {
            snapshot = new ArrayList<>(sessions.entrySet());
        }
        for (Map.Entry<String, ActiveSession> item : snapshot) {
            if (item.getValue().detachAsrStreams(brokerIdentity)) {
                notifyStatus(item.getKey(), -3, "asr_broker_disconnected_recording_continues");
            }
        }
    }

    private void handleAsrStatus(
            String callId, String direction, Object streamIdentity, String detail) {
        ActiveSession session;
        synchronized (sessions) {
            session = sessions.get(callId);
        }
        if (asr.acceptsCallback(streamIdentity)
                && session != null
                && session.acceptsAsrCallback(direction, streamIdentity)) {
            notifyStatus(callId, 3, direction + ":" + detail);
        }
    }

    private void requestReceptionistReply(
            String callId, ActiveSession session, String language, String text) {
        AssistantTurnQueue.CallerTurn turn = session.offerCallerTurn(language, text);
        if (turn == null) return;
        if (!receptionist.requestReply(callId, turn.language, turn.text)) {
            notifyStatus(callId, -5, "receptionist_request_unavailable");
            continueAfterAssistantOperation(callId, session);
        } else {
            notifyStatus(callId, 6, "receptionist_thinking");
        }
    }

    private void handleReceptionistReply(
            String callId, ReceptionistDialogueClient.Reply reply) {
        ActiveSession session;
        synchronized (sessions) {
            session = sessions.get(callId);
        }
        if (session == null || !session.isAiHandling()) return;
        publishAssessment(
                callId,
                session,
                session.observeModel(new CallClassifierClient.ModelAssessment(
                        reply.riskScore,
                        reply.label,
                        reply.language,
                        reply.reasonCode)));
        try {
            session.stored.appendAssistantReply(
                    reply.language, reply.text, System.currentTimeMillis());
        } catch (IOException error) {
            notifyStatus(callId, -6, "assistant_reply_storage_failed");
        }
        session.appendContextAssistantReply(reply.language, reply.text);
        speakToCaller(callId, session, reply.language, reply.text);
    }

    private void handleReceptionistStatus(String callId, String detail) {
        notifyStatus(callId, 6, detail);
        if (callId == null || "availability".equals(callId)
                || detail == null || "receptionist_ready".equals(detail)) return;
        ActiveSession session;
        synchronized (sessions) {
            session = sessions.get(callId);
        }
        if (session != null && session.isAiHandling()) {
            continueAfterAssistantOperation(callId, session);
        }
    }

    private void speakToCaller(
            String callId, ActiveSession session, String language, String text) {
        SpeechSynthesisBrokerClient.Speech synthesized = null;
        CallerAudioUplink.Stream uplink = null;
        try {
            long generation = nextSpeechRequestSerial();
            synthesized = speech.synthesize(
                    callId + ":tts:" + generation, language, text);
            uplink = callerAudio.open(
                    callId,
                    synthesized.takePcmInput(),
                    synthesized.sampleRateHz,
                    (completedCallId, detail) ->
                            handleCallerAudioStatus(completedCallId, session, detail));
            if (!session.attachAssistantAudio(synthesized, uplink)) {
                throw new IOException("call ended during assistant audio setup");
            }
            uplink.start();
            notifyStatus(callId, 7, "assistant_speaking");
        } catch (IOException | RuntimeException error) {
            if (uplink != null) uplink.close();
            if (synthesized != null) synthesized.close();
            notifyStatus(callId, -7, "assistant_speech_unavailable");
            continueAfterAssistantOperation(callId, session);
        }
    }

    private void handleCallerAudioStatus(
            String callId, ActiveSession expectedSession, String detail) {
        ActiveSession session;
        synchronized (sessions) {
            session = sessions.get(callId);
        }
        if (session != expectedSession || !session.isOpen()) return;
        notifyStatus(callId, 7, detail);
        if (!"caller_audio_complete".equals(detail)
                && !"caller_audio_failed".equals(detail)) return;
        continueAfterAssistantOperation(callId, session);
    }

    private void continueAfterAssistantOperation(String callId, ActiveSession session) {
        ActiveSession.AssistantCompletion completion = session.completeAssistantOperation();
        completion.closeAudio();
        if (completion.nextTurn != null) {
            if (!receptionist.requestReply(
                    callId, completion.nextTurn.language, completion.nextTurn.text)) {
                notifyStatus(callId, -5, "receptionist_request_unavailable");
                session.completeAssistantOperation().closeAudio();
            } else {
                notifyStatus(callId, 6, "receptionist_thinking");
            }
        }
    }

    private void handleModelAssessment(
            String callId, CallClassifierClient.ModelAssessment modelAssessment) {
        ActiveSession session;
        synchronized (sessions) {
            session = sessions.get(callId);
        }
        if (session != null) {
            publishAssessment(callId, session, session.observeModel(modelAssessment));
        }
    }

    private void publishAssessment(
            String callId, ActiveSession session, RiskAssessmentTracker.Update update) {
        if (update == null) return;
        try {
            session.stored.appendAssessment(
                    update.assessment.score,
                    update.assessment.label,
                    update.assessment.reasonCode,
                    update.source,
                    update.revision,
                    update.observedAtEpochMillis);
        } catch (IOException error) {
            notifyStatus(callId, -3, "assessment_storage_failed");
        }
        session.appendContextAssessment(
                update.assessment.score,
                update.assessment.label,
                update.assessment.reasonCode);
        notifyRisk(toRiskAssessment(callId, update));
    }

    private static CallRiskAssessment toRiskAssessment(
            String callId, RiskAssessmentTracker.Update update) {
        CallRiskAssessment value = new CallRiskAssessment();
        value.callId = callId;
        value.riskScore = update.assessment.score;
        value.label = update.assessment.label;
        value.reasonCode = update.assessment.reasonCode;
        value.source = update.source;
        value.revision = update.revision;
        value.observedAtEpochMillis = update.observedAtEpochMillis;
        return value;
    }

    private void publishAssistantState(
            String callId, ActiveSession session, AssistantHandlingTracker.Update update) {
        if (update == null) return;
        try {
            session.stored.appendAssistantState(
                    update.aiHandling, update.revision, update.observedAtEpochMillis);
        } catch (IOException error) {
            notifyStatus(callId, -9, "assistant_state_storage_failed");
        }
        notifyAssistantState(toAssistantState(callId, update));
    }

    private static CallAssistantState toAssistantState(
            String callId, AssistantHandlingTracker.Update update) {
        CallAssistantState value = new CallAssistantState();
        value.callId = callId;
        value.aiHandling = update.aiHandling;
        value.revision = update.revision;
        value.observedAtEpochMillis = update.observedAtEpochMillis;
        return value;
    }

    private synchronized long nextSpeechRequestSerial() throws IOException {
        if (nextSpeechRequestSerial == Long.MAX_VALUE) {
            throw new IOException("speech request identity exhausted");
        }
        return ++nextSpeechRequestSerial;
    }

    private static java.io.OutputStream sink(AsrBrokerClient.Stream stream) {
        return stream == null ? null : stream.sink;
    }

    private static void closeQuietly(java.io.OutputStream stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (IOException ignored) {
            // Best effort after capture construction fails.
        }
    }

    private static final class ActiveSession implements AutoCloseable {
        private static final class ContextRecord {
            final CallCommunicationContextClient.PreparedContext prepared;
            final String sourceId;
            final long revision;
            final long eventAtEpochMillis;
            final long expiresAtEpochMillis;
            final String text;

            ContextRecord(
                    CallCommunicationContextClient.PreparedContext prepared,
                    String sourceId,
                    long revision,
                    long eventAtEpochMillis,
                    long expiresAtEpochMillis,
                    String text) {
                this.prepared = prepared;
                this.sourceId = sourceId;
                this.revision = revision;
                this.eventAtEpochMillis = eventAtEpochMillis;
                this.expiresAtEpochMillis = expiresAtEpochMillis;
                this.text = text;
            }
        }

        private static final class TakeoverResult {
            final AssistantHandlingTracker.Update update;
            final boolean knownContact;
            final AssistantCompletion completion;

            TakeoverResult(
                    AssistantHandlingTracker.Update update,
                    boolean knownContact,
                    AssistantCompletion completion) {
                this.update = update;
                this.knownContact = knownContact;
                this.completion = completion;
            }

            void closeAudio() {
                completion.closeAudio();
            }
        }

        private static final class AssistantCompletion {
            final SpeechSynthesisBrokerClient.Speech speech;
            final CallerAudioUplink.Stream uplink;
            final AssistantTurnQueue.CallerTurn nextTurn;

            AssistantCompletion(
                    SpeechSynthesisBrokerClient.Speech speech,
                    CallerAudioUplink.Stream uplink,
                    AssistantTurnQueue.CallerTurn nextTurn) {
                this.speech = speech;
                this.uplink = uplink;
                this.nextTurn = nextTurn;
            }

            void closeAudio() {
                if (uplink != null) uplink.close();
                if (speech != null) speech.close();
            }
        }

        private final CallArtifactStore.Session stored;
        private final TelephonyAudioCapture capture;
        private final ResilientFanoutOutputStream downlinkFanout;
        private final ResilientFanoutOutputStream uplinkFanout;
        private AsrBrokerClient.Stream downlinkAsr;
        private AsrBrokerClient.Stream uplinkAsr;
        private final RiskAssessmentTracker risk;
        private final AssistantHandlingTracker assistantHandling;
        private final int ownerUid;
        private final boolean knownContact;
        private final AssistantTurnQueue turnQueue = new AssistantTurnQueue();
        private final CallContextAccumulator communicationSummary =
                new CallContextAccumulator();
        private CallCommunicationContextClient.PreparedContext communicationContext;
        private boolean closed;
        private SpeechSynthesisBrokerClient.Speech activeSpeech;
        private CallerAudioUplink.Stream activeUplink;
        private final TranscriptRevisionGate classifierTranscriptRevisions =
                new TranscriptRevisionGate();

        ActiveSession(
                CallArtifactStore.Session stored,
                TelephonyAudioCapture capture,
                ResilientFanoutOutputStream downlinkFanout,
                ResilientFanoutOutputStream uplinkFanout,
                AsrBrokerClient.Stream downlinkAsr,
                AsrBrokerClient.Stream uplinkAsr,
                SpamRiskEngine risk,
                int ownerUid,
                boolean answeredByAi,
                boolean knownContact,
                CallCommunicationContextClient.PreparedContext communicationContext) {
            this.stored = stored;
            this.capture = capture;
            this.downlinkFanout = downlinkFanout;
            this.uplinkFanout = uplinkFanout;
            this.downlinkAsr = downlinkAsr;
            this.uplinkAsr = uplinkAsr;
            this.risk = new RiskAssessmentTracker(risk);
            this.ownerUid = ownerUid;
            assistantHandling = new AssistantHandlingTracker(answeredByAi);
            this.knownContact = knownContact;
            this.communicationContext = communicationContext;
        }

        synchronized void setCommunicationContext(
                CallCommunicationContextClient.PreparedContext prepared) {
            if (!closed && prepared != null && prepared.identity != null) {
                communicationContext = prepared;
            }
        }

        synchronized boolean clearCommunicationContext() {
            if (closed || communicationContext == null) return false;
            communicationContext = null;
            return true;
        }

        void appendContextTranscript(
                String direction, String language, String text, boolean isFinal) {
            communicationSummary.appendTranscript(direction, language, text, isFinal);
        }

        void appendContextAssistantReply(String language, String text) {
            communicationSummary.appendAssistantReply(language, text);
        }

        void appendContextAssessment(int score, String label, String reasonCode) {
            communicationSummary.appendAssessment(score, label, reasonCode);
        }

        synchronized ContextRecord contextRecord(int disconnectCause, long revision) {
            if (closed || revision <= 0L) return null;
            return new ContextRecord(
                    communicationContext,
                    stored.sourceId,
                    revision,
                    stored.createdAtEpochMillis,
                    stored.expiresAtEpochMillis,
                    communicationSummary.finish(disconnectCause));
        }

        synchronized RiskAssessmentTracker.Update initialAssessment() {
            return closed ? null : risk.initial();
        }

        synchronized RiskAssessmentTracker.Update currentRiskUpdate() {
            return risk.current();
        }

        synchronized AssistantHandlingTracker.Update initialAssistantState() {
            return closed ? null : assistantHandling.initial();
        }

        synchronized AssistantHandlingTracker.Update currentAssistantState() {
            return assistantHandling.current();
        }

        synchronized boolean isAiHandling() {
            return !closed && assistantHandling.isAiHandling();
        }

        synchronized boolean isOpen() {
            return !closed;
        }

        synchronized boolean ownedBy(int candidateUid) {
            return ownerUid == candidateUid;
        }

        synchronized boolean acceptsAsrCallback(String direction, Object streamIdentity) {
            if (closed || streamIdentity == null) return false;
            AsrBrokerClient.Stream expected = "downlink".equals(direction)
                    ? downlinkAsr
                    : "uplink".equals(direction) ? uplinkAsr : null;
            return expected != null && expected.identity == streamIdentity;
        }

        synchronized boolean needsAsrRestore(Object brokerIdentity) {
            return !closed && brokerIdentity != null
                    && (downlinkAsr == null
                    || downlinkAsr.brokerIdentity != brokerIdentity
                    || uplinkAsr == null
                    || uplinkAsr.brokerIdentity != brokerIdentity);
        }

        synchronized boolean detachAsrStreams(Object brokerIdentity) {
            if (closed || brokerIdentity == null
                    || ((downlinkAsr == null
                    || downlinkAsr.brokerIdentity != brokerIdentity)
                    && (uplinkAsr == null
                    || uplinkAsr.brokerIdentity != brokerIdentity))) {
                return false;
            }
            AsrBrokerClient.Stream previousDownlink = downlinkAsr;
            AsrBrokerClient.Stream previousUplink = uplinkAsr;
            downlinkAsr = null;
            uplinkAsr = null;
            downlinkFanout.replaceSecondary(null);
            uplinkFanout.replaceSecondary(null);
            if (previousDownlink != null) previousDownlink.close();
            if (previousUplink != null) previousUplink.close();
            return true;
        }

        synchronized boolean replaceAsrStreams(
                Object brokerIdentity,
                AsrBrokerClient.Stream downlink,
                AsrBrokerClient.Stream uplink) {
            if (closed || brokerIdentity == null || downlink == null
                    || downlink.brokerIdentity != brokerIdentity
                    || (uplink != null && uplink.brokerIdentity != brokerIdentity)) {
                return false;
            }
            AsrBrokerClient.Stream previousDownlink = downlinkAsr;
            AsrBrokerClient.Stream previousUplink = uplinkAsr;
            if (!downlinkFanout.replaceSecondary(downlink.sink)
                    || !uplinkFanout.replaceSecondary(sink(uplink))) {
                return false;
            }
            downlinkAsr = downlink;
            uplinkAsr = uplink;
            if (previousDownlink != null) previousDownlink.close();
            if (previousUplink != null) previousUplink.close();
            return true;
        }

        synchronized TakeoverResult takeOver() {
            if (closed) return null;
            AssistantHandlingTracker.Update update = assistantHandling.takeOver();
            if (update == null) return null;
            turnQueue.close();
            AssistantCompletion completion = new AssistantCompletion(
                    activeSpeech, activeUplink, null);
            activeSpeech = null;
            activeUplink = null;
            return new TakeoverResult(update, knownContact, completion);
        }

        synchronized boolean beginGreeting() {
            return !closed && assistantHandling.isAiHandling()
                    && turnQueue.beginGreeting();
        }

        synchronized AssistantTurnQueue.CallerTurn offerCallerTurn(
                String language, String text) {
            if (closed || !assistantHandling.isAiHandling()) return null;
            return turnQueue.offer(language, text);
        }

        synchronized boolean attachAssistantAudio(
                SpeechSynthesisBrokerClient.Speech speech,
                CallerAudioUplink.Stream uplink) {
            if (closed || !assistantHandling.isAiHandling() || !turnQueue.isBusy()
                    || activeSpeech != null || activeUplink != null) {
                return false;
            }
            activeSpeech = speech;
            activeUplink = uplink;
            return true;
        }

        synchronized AssistantCompletion completeAssistantOperation() {
            SpeechSynthesisBrokerClient.Speech speech = activeSpeech;
            CallerAudioUplink.Stream uplink = activeUplink;
            activeSpeech = null;
            activeUplink = null;
            AssistantTurnQueue.CallerTurn next = closed ? null : turnQueue.complete();
            return new AssistantCompletion(speech, uplink, next);
        }

        synchronized RiskAssessmentTracker.Update observeHeuristicRevision(
                String text, String language, boolean isFinal, long transcriptRevision) {
            if (closed || !classifierTranscriptRevisions.advance(transcriptRevision)) {
                return null;
            }
            return risk.observeHeuristicRevision(text, language, isFinal);
        }

        synchronized RiskAssessmentTracker.Update observeModel(
                CallClassifierClient.ModelAssessment candidate) {
            if (closed || candidate == null
                    || !classifierTranscriptRevisions.accepts(
                            candidate.transcriptRevision)) {
                return null;
            }
            return risk.observeModelRevision(
                    candidate.riskScore,
                    candidate.label,
                    candidate.reasonCode,
                    candidate.finalTranscript);
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            turnQueue.close();
            if (activeUplink != null) activeUplink.close();
            if (activeSpeech != null) activeSpeech.close();
            activeUplink = null;
            activeSpeech = null;
            capture.close();
            if (downlinkAsr != null) {
                downlinkAsr.close();
            }
            if (uplinkAsr != null) {
                uplinkAsr.close();
            }
            stored.close();
        }
    }
}
