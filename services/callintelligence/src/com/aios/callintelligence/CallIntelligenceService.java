package com.aios.callintelligence;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.SystemProperties;

import com.aios.call.CallHandlingDecision;
import com.aios.call.CallAssistantPolicy;
import com.aios.call.IAiosCallIntelligence;
import com.aios.call.ICallIntelligenceListener;
import com.aios.call.IncomingCallContext;
import com.aios.call.TranscriptSegment;
import com.aios.model.GenerationChunk;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class CallIntelligenceService extends Service {
    private static final String PERMISSION_CONTROL =
            "com.aios.permission.CONTROL_CALL_INTELLIGENCE";
    private static final long DEFAULT_MISSED_DELAY_MILLIS = 15_000L;
    private static final String CALL_UPLINK_VALIDATION_PROPERTY =
            "ro.aios.call_uplink_validated";

    private final RemoteCallbackList<ICallIntelligenceListener> listeners =
            new RemoteCallbackList<>();
    private final Map<String, ActiveSession> sessions = new HashMap<>();
    private final Map<String, Boolean> pendingKnownContacts = new HashMap<>();
    private CallArtifactStore artifactStore;
    private AsrBrokerClient asr;
    private CallClassifierClient classifier;
    private CallerAudioUplink callerAudio;
    private SpeechSynthesisBrokerClient speech;

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
            synchronized (sessions) {
                pendingKnownContacts.remove(callId);
                classifier.endCall(callId);
                stopLocked(callId);
            }
            artifactStore.cleanup(System.currentTimeMillis());
            synchronized (sessions) {
                if (sessions.isEmpty()) {
                    asr.setCallActive(false);
                }
            }
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
        synchronized (sessions) {
            pendingKnownContacts.clear();
            for (ActiveSession session : sessions.values()) {
                session.close();
            }
            sessions.clear();
        }
        if (speech != null) speech.close();
        if (callerAudio != null) callerAudio.close();
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
                && callerAudio != null
                && callerAudio.probe().available
                && speech != null
                && speech.isAvailable("en")
                && speech.isAvailable("es");
    }

    private String automaticAnswerUnavailableReason() {
        if (!SystemProperties.getBoolean(CALL_UPLINK_VALIDATION_PROPERTY, false)) {
            return "caller_audio_injection_requires_physical_validation";
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
        return "caller_interaction_transport_unavailable";
    }

    private void beginCapture(String callId, boolean answeredByAi, boolean knownContact) {
        synchronized (sessions) {
            beginCaptureLocked(callId, answeredByAi, knownContact);
        }
    }

    private void beginCaptureLocked(
            String callId, boolean answeredByAi, boolean knownContact) {
        if (sessions.containsKey(callId)) {
            notifyStatus(callId, 1, "capture_already_started");
            return;
        }
        CallArtifactStore.Session stored = null;
        AsrBrokerClient.Stream downlinkAsr = null;
        AsrBrokerClient.Stream uplinkAsr = null;
        TelephonyAudioCapture capture = null;
        try {
            stored = artifactStore.create(callId, answeredByAi, System.currentTimeMillis());
            asr.setCallActive(true);
            downlinkAsr = asr.openStream(callId, "downlink");
            uplinkAsr = asr.openStream(callId, "uplink");
            capture = new TelephonyAudioCapture(
                    new ResilientFanoutOutputStream(
                            stored.openDownlink(), sink(downlinkAsr)),
                    new ResilientFanoutOutputStream(
                            stored.openUplink(), sink(uplinkAsr)));
            ActiveSession active = new ActiveSession(
                    stored, capture, downlinkAsr, uplinkAsr,
                    new SpamRiskEngine(knownContact));
            capture.start();
            classifier.beginCall(callId, knownContact);
            sessions.put(callId, active);
        } catch (IOException | RuntimeException error) {
            if (capture != null) capture.close();
            if (downlinkAsr != null) downlinkAsr.close();
            if (uplinkAsr != null) uplinkAsr.close();
            if (stored != null) stored.close();
            if (sessions.isEmpty()) asr.setCallActive(false);
            notifyStatus(callId, -1, "capture_unavailable");
            return;
        }
        RetentionAlarm.scheduleNext(this, artifactStore);
        notifyStatus(callId, 1, "capture_started");
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
        if ("downlink".equals(direction) && chunk.text != null && !chunk.text.isBlank()) {
            SpamRiskEngine.Assessment assessment =
                    session.observeHeuristic(chunk.text, language);
            publishAssessment(callId, session, assessment);
            classifier.observe(callId, language, chunk.text);
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
        private final CallArtifactStore.Session stored;
        private final TelephonyAudioCapture capture;
        private final AsrBrokerClient.Stream downlinkAsr;
        private final AsrBrokerClient.Stream uplinkAsr;
        private final SpamRiskEngine risk;
        private SpamRiskEngine.Assessment published;
        private CallClassifierClient.ModelAssessment modelAssessment;

        ActiveSession(
                CallArtifactStore.Session stored,
                TelephonyAudioCapture capture,
                AsrBrokerClient.Stream downlinkAsr,
                AsrBrokerClient.Stream uplinkAsr,
                SpamRiskEngine risk) {
            this.stored = stored;
            this.capture = capture;
            this.downlinkAsr = downlinkAsr;
            this.uplinkAsr = uplinkAsr;
            this.risk = risk;
            published = risk.current();
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
        public void close() {
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
