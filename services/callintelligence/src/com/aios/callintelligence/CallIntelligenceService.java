package com.aios.callintelligence;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemProperties;

import com.aios.call.CallHandlingDecision;
import com.aios.call.CallAssistantPolicy;
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
    private static final String CALL_UPLINK_VALIDATION_PROPERTY =
            "ro.aios.call_uplink_validated";
    private static final int MAX_TELECOM_LIFECYCLE_TOKENS = 4;
    private static final int MAX_CALLS_PER_LIFECYCLE_TOKEN = 8;

    private final RemoteCallbackList<ICallIntelligenceListener> listeners =
            new RemoteCallbackList<>();
    private final Map<String, ActiveSession> sessions = new HashMap<>();
    private final Map<String, Boolean> pendingKnownContacts = new HashMap<>();
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
    private Handler mainHandler;
    private boolean appliedTelecomPresence;
    private boolean telecomPresenceUpdateScheduled;
    private boolean telecomPresenceStopping;

    private final IAiosCallIntelligence.Stub binder = new IAiosCallIntelligence.Stub() {
        @Override
        public CallAssistantPolicy getPolicy() {
            enforceControlPermission();
            return readPolicy();
        }

        @Override
        public CallAssistantPolicy updatePolicy(CallAssistantPolicy requested) {
            enforceControlPermission();
            if (requested == null || !CallPolicyEngine.isKnownMode(requested.answerMode)
                    || !AnswerDelayPolicy.isKnownMode(requested.answerDelayMode)
                    || requested.missedDelayMillis < 3_000L
                    || requested.missedDelayMillis > 60_000L) {
                throw new IllegalArgumentException("invalid call-assistant policy");
            }
            SharedPreferences.Editor editor = ownerPreferences().edit()
                    .putString("answer_mode", requested.answerMode)
                    .putString("answer_delay_mode", requested.answerDelayMode)
                    .putLong("missed_delay_ms", requested.missedDelayMillis)
                    .putBoolean("processing_enabled", requested.processingEnabled);
            if (!editor.commit()) {
                throw new IllegalStateException("call-assistant policy could not be saved");
            }
            return readPolicy();
        }

        @Override
        public CallHandlingDecision evaluateIncoming(IncomingCallContext context) {
            enforceControlPermission();
            if (context != null && context.callId != null && !context.callId.isEmpty()) {
                synchronized (sessions) {
                    if (pendingKnownContacts.size() >= 64) {
                        pendingKnownContacts.clear();
                    }
                    pendingKnownContacts.put(context.callId, context.knownContact);
                }
            }
            return currentPolicy().evaluate(context);
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
            enforceControlPermission();
            if (callId == null || callId.isEmpty()) {
                notifyStatus(callId, 0, "invalid_call_id");
                return;
            }
            boolean knownContact;
            synchronized (sessions) {
                knownContact = Boolean.TRUE.equals(pendingKnownContacts.remove(callId));
            }
            if (!processingAllowed) {
                notifyStatus(callId, 0, "processing_not_allowed");
                return;
            }

            if (!answeredByAi) {
                beginCapture(callId, false, knownContact);
                return;
            }

            if (!callerInteractionTransportReady()) {
                notifyStatus(callId, -4, automaticAnswerUnavailableReason());
                return;
            }
            beginCapture(callId, true, knownContact);
        }

        @Override
        public void onCallEnded(String callId, int disconnectCause) {
            enforceControlPermission();
            classifier.endCall(callId);
            receptionist.endCall(callId);
            synchronized (sessions) {
                pendingKnownContacts.remove(callId);
                stopLocked(callId);
            }
            artifactStore.cleanup(System.currentTimeMillis());
            RetentionAlarm.scheduleNext(CallIntelligenceService.this, artifactStore);
            notifyStatus(callId, 2, "call_ended");
        }

        @Override
        public void registerListener(ICallIntelligenceListener listener) {
            enforceControlPermission();
            if (listener != null) {
                listeners.register(listener);
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

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        artifactStore = new CallArtifactStore(this);
        artifactStore.cleanup(System.currentTimeMillis());
        RetentionAlarm.scheduleNext(this, artifactStore);
        asr = new AsrBrokerClient(this, new AsrBrokerClient.Listener() {
            @Override
            public void onTranscript(
                    String callId,
                    String direction,
                    String language,
                    GenerationChunk chunk) {
                handleTranscript(callId, direction, language, chunk);
            }

            @Override
            public void onAsrStatus(String callId, String direction, String detail) {
                notifyStatus(callId, 3, direction + ":" + detail);
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
            pendingKnownContacts.clear();
            for (ActiveSession session : sessions.values()) {
                session.close();
            }
            sessions.clear();
        }
        if (speech != null) speech.close();
        if (callerAudio != null) callerAudio.close();
        if (receptionist != null) receptionist.close();
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
        value.automaticAnswerAvailable = callerInteractionTransportReady();
        value.automaticAnswerUnavailableReason = value.automaticAnswerAvailable
                ? "" : automaticAnswerUnavailableReason();
        return value;
    }

    private boolean callerInteractionTransportReady() {
        return SystemProperties.getBoolean(CALL_UPLINK_VALIDATION_PROPERTY, false)
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
        if (!SystemProperties.getBoolean(CALL_UPLINK_VALIDATION_PROPERTY, false)) {
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

    private void beginCapture(String callId, boolean answeredByAi, boolean knownContact) {
        ActiveSession started;
        synchronized (sessions) {
            started = beginCaptureLocked(callId, answeredByAi, knownContact);
        }
        if (started != null && answeredByAi && started.beginGreeting()) {
            String language = "es".equals(Locale.getDefault().getLanguage()) ? "es" : "en";
            String greeting = "es".equals(language)
                    ? "Hola, ¿cómo puedo ayudarle?"
                    : "Hello, how can I help you?";
            speakToCaller(callId, started, language, greeting);
        }
    }

    private ActiveSession beginCaptureLocked(
            String callId, boolean answeredByAi, boolean knownContact) {
        if (sessions.containsKey(callId)) {
            notifyStatus(callId, 1, "capture_already_started");
            return null;
        }
        CallArtifactStore.Session stored = null;
        AsrBrokerClient.Stream downlinkAsr = null;
        AsrBrokerClient.Stream uplinkAsr = null;
        TelephonyAudioCapture capture = null;
        try {
            stored = artifactStore.create(callId, answeredByAi, System.currentTimeMillis());
            downlinkAsr = asr.openStream(callId, "downlink");
            uplinkAsr = asr.openStream(callId, "uplink");
            if (answeredByAi && downlinkAsr == null) {
                throw new IOException("incoming ASR is required for AI answering");
            }
            capture = new TelephonyAudioCapture(
                    new ResilientFanoutOutputStream(
                            stored.openDownlink(), sink(downlinkAsr)),
                    new ResilientFanoutOutputStream(
                            stored.openUplink(), sink(uplinkAsr)));
            ActiveSession active = new ActiveSession(
                    stored, capture, downlinkAsr, uplinkAsr,
                    new SpamRiskEngine(knownContact), answeredByAi);
            sessions.put(callId, active);
            if (answeredByAi) {
                receptionist.beginCall(callId, knownContact);
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
            if (downlinkAsr != null) downlinkAsr.close();
            if (uplinkAsr != null) uplinkAsr.close();
            if (stored != null) stored.close();
            notifyStatus(callId, -1, "capture_unavailable");
            return null;
        }
    }

    private void stopLocked(String callId) {
        ActiveSession session = sessions.remove(callId);
        if (session != null) {
            session.close();
        }
    }

    private void enforceControlPermission() {
        enforceCallingOrSelfPermission(PERMISSION_CONTROL, "unauthorized dialer caller");
    }

    private void updateTelecomPresence(
            int ownerUid, IBinder token, String callId, boolean present) {
        if (token == null || callId == null || callId.isEmpty() || callId.length() > 128) {
            throw new IllegalArgumentException("valid Telecom token and opaque call ID required");
        }
        IBinder.DeathRecipient removedRecipient = null;
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
                telecomPresence.setPresent(token, ownerUid, callId, present);
                if (!present && telecomPresence.ownerUid(token) == null) {
                    removedRecipient = telecomPresenceDeaths.remove(token);
                }
            }
            scheduleTelecomPresenceReconciliationLocked();
        }
        if (removedRecipient != null) {
            token.unlinkToDeath(removedRecipient, 0);
        }
    }

    private void onTelecomPresenceTokenDied(IBinder token) {
        synchronized (telecomPresenceLock) {
            if (telecomPresenceStopping) {
                return;
            }
            telecomPresenceDeaths.remove(token);
            telecomPresence.removeDead(token);
            scheduleTelecomPresenceReconciliationLocked();
        }
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

    private void notifyRisk(String callId, SpamRiskEngine.Assessment assessment) {
        int count = listeners.beginBroadcast();
        try {
            for (int index = 0; index < count; index++) {
                try {
                    listeners.getBroadcastItem(index).onRiskChanged(
                            callId,
                            assessment.score,
                            assessment.label + ":" + assessment.reasonCode);
                } catch (Exception ignored) {
                    // Dead listeners are removed by RemoteCallbackList.
                }
            }
        } finally {
            listeners.finishBroadcast();
        }
    }

    private void handleTranscript(
            String callId, String direction, String language, GenerationChunk chunk) {
        ActiveSession session;
        synchronized (sessions) {
            session = sessions.get(callId);
        }
        if (session == null) {
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
        TranscriptSegment segment = new TranscriptSegment();
        segment.callId = callId;
        segment.direction = direction;
        segment.language = language;
        segment.text = chunk.text;
        segment.isFinal = chunk.isFinal;
        segment.confidence = chunk.confidence;
        segment.startMillis = chunk.sourceStartMillis;
        segment.endMillis = chunk.sourceEndMillis;
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
        if ("downlink".equals(direction) && chunk.isFinal
                && chunk.text != null && !chunk.text.isBlank()) {
            SpamRiskEngine.Assessment assessment =
                    session.observeHeuristic(chunk.text, language);
            publishAssessment(callId, session, assessment);
            if (session.answeredByAi) {
                requestReceptionistReply(callId, session, language, chunk.text);
            } else {
                classifier.observe(callId, language, chunk.text);
            }
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
        if (session == null || !session.answeredByAi) return;
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
        if (session != null && session.answeredByAi) {
            continueAfterAssistantOperation(callId, session);
        }
    }

    private void speakToCaller(
            String callId, ActiveSession session, String language, String text) {
        SpeechSynthesisBrokerClient.Speech synthesized = null;
        CallerAudioUplink.Stream uplink = null;
        try {
            long generation = session.nextSpeechGeneration();
            synthesized = speech.synthesize(
                    callId + ":tts:" + generation, language, text);
            uplink = callerAudio.open(
                    callId,
                    synthesized.takePcmInput(),
                    synthesized.sampleRateHz,
                    (completedCallId, detail) ->
                            handleCallerAudioStatus(completedCallId, detail));
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

    private void handleCallerAudioStatus(String callId, String detail) {
        notifyStatus(callId, 7, detail);
        if (!"caller_audio_complete".equals(detail)
                && !"caller_audio_failed".equals(detail)) return;
        ActiveSession session;
        synchronized (sessions) {
            session = sessions.get(callId);
        }
        if (session != null) continueAfterAssistantOperation(callId, session);
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
            String callId, ActiveSession session, SpamRiskEngine.Assessment assessment) {
        if (assessment == null) return;
        String source = assessment.reasonCode.startsWith("model_") ? "model" : "heuristic";
        try {
            session.stored.appendAssessment(
                    assessment.score,
                    assessment.label,
                    assessment.reasonCode,
                    source,
                    System.currentTimeMillis());
        } catch (IOException error) {
            notifyStatus(callId, -3, "assessment_storage_failed");
        }
        notifyRisk(callId, assessment);
    }

    private static java.io.OutputStream sink(AsrBrokerClient.Stream stream) {
        return stream == null ? null : stream.sink;
    }

    private static final class ActiveSession implements AutoCloseable {
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
        private final AsrBrokerClient.Stream downlinkAsr;
        private final AsrBrokerClient.Stream uplinkAsr;
        private final SpamRiskEngine risk;
        private final boolean answeredByAi;
        private final AssistantTurnQueue turnQueue = new AssistantTurnQueue();
        private SpamRiskEngine.Assessment published;
        private CallClassifierClient.ModelAssessment modelAssessment;
        private boolean closed;
        private long speechGeneration;
        private SpeechSynthesisBrokerClient.Speech activeSpeech;
        private CallerAudioUplink.Stream activeUplink;

        ActiveSession(
                CallArtifactStore.Session stored,
                TelephonyAudioCapture capture,
                AsrBrokerClient.Stream downlinkAsr,
                AsrBrokerClient.Stream uplinkAsr,
                SpamRiskEngine risk,
                boolean answeredByAi) {
            this.stored = stored;
            this.capture = capture;
            this.downlinkAsr = downlinkAsr;
            this.uplinkAsr = uplinkAsr;
            this.risk = risk;
            this.answeredByAi = answeredByAi;
            published = risk.current();
        }

        synchronized boolean beginGreeting() {
            return !closed && answeredByAi && turnQueue.beginGreeting();
        }

        synchronized AssistantTurnQueue.CallerTurn offerCallerTurn(
                String language, String text) {
            if (closed || !answeredByAi) return null;
            return turnQueue.offer(language, text);
        }

        synchronized long nextSpeechGeneration() {
            return ++speechGeneration;
        }

        synchronized boolean attachAssistantAudio(
                SpeechSynthesisBrokerClient.Speech speech,
                CallerAudioUplink.Stream uplink) {
            if (closed || !turnQueue.isBusy()
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

        synchronized SpamRiskEngine.Assessment observeHeuristic(String text, String language) {
            risk.observe(text, language);
            return changedCombined();
        }

        synchronized SpamRiskEngine.Assessment observeModel(
                CallClassifierClient.ModelAssessment candidate) {
            if (modelAssessment == null || candidate.riskScore > modelAssessment.riskScore
                    || (modelAssessment.riskScore <= 15
                    && candidate.label.equals(SpamRiskEngine.LIKELY_LEGITIMATE))) {
                modelAssessment = candidate;
            }
            return changedCombined();
        }

        private SpamRiskEngine.Assessment changedCombined() {
            SpamRiskEngine.Assessment heuristic = risk.current();
            SpamRiskEngine.Assessment combined = heuristic;
            if (modelAssessment != null
                    && (modelAssessment.riskScore > heuristic.score
                    || (SpamRiskEngine.UNKNOWN.equals(heuristic.label)
                    && SpamRiskEngine.LIKELY_LEGITIMATE.equals(modelAssessment.label)
                    && heuristic.score <= 15))) {
                combined = new SpamRiskEngine.Assessment(
                        modelAssessment.riskScore,
                        modelAssessment.label,
                        "model_" + modelAssessment.reasonCode);
            }
            if (!combined.differsFrom(published)) return null;
            published = combined;
            return combined;
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
