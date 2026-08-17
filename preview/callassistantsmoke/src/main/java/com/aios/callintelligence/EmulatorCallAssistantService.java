package com.aios.callintelligence;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.SystemClock;

import com.aios.call.CallAssistantPolicy;
import com.aios.call.CallHandlingDecision;
import com.aios.call.IAiosCallIntelligence;
import com.aios.call.ICallIntelligenceListener;
import com.aios.call.IncomingCallContext;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Emulator-only AIDL peer for driving the production Phone timer through Telecom.
 *
 * It intentionally supplies no capture, ASR, model, or caller-audio implementation.
 * Its only purpose is to return decisions from the production policy classes and
 * record identifier-free lifecycle evidence for the host runner.
 */
public final class EmulatorCallAssistantService extends Service {
    static final String PREFERENCES = "emulator_call_assistant_policy";
    static final String KEY_AVAILABLE = "available";
    static final String KEY_ANSWER_MODE = "answer_mode";
    static final String KEY_DELAY_MODE = "answer_delay_mode";
    static final String KEY_PROCESSING_ENABLED = "processing_enabled";
    static final String AUDIT_FILE = "aios-call-assistant-smoke-audit.txt";

    private static final Object AUDIT_LOCK = new Object();
    private static EmulatorCallAssistantService activeInstance;

    private final RemoteCallbackList<ICallIntelligenceListener> listeners =
            new RemoteCallbackList<>();

    private final IAiosCallIntelligence.Stub binder = new IAiosCallIntelligence.Stub() {
        @Override
        public CallAssistantPolicy getPolicy() {
            return readPolicy();
        }

        @Override
        public CallAssistantPolicy updatePolicy(CallAssistantPolicy requested) {
            if (requested == null || !CallPolicyEngine.isKnownMode(requested.answerMode)
                    || !AnswerDelayPolicy.isKnownMode(requested.answerDelayMode)) {
                throw new IllegalArgumentException("invalid fixture policy");
            }
            boolean committed = preferences().edit()
                    .putString(KEY_ANSWER_MODE, requested.answerMode)
                    .putString(KEY_DELAY_MODE, requested.answerDelayMode)
                    .putBoolean(KEY_PROCESSING_ENABLED, requested.processingEnabled)
                    .commit();
            if (!committed) throw new IllegalStateException("fixture policy could not be saved");
            audit("policy_update:" + requested.answerMode + ":"
                    + requested.answerDelayMode + ":" + requested.processingEnabled);
            return readPolicy();
        }

        @Override
        public CallHandlingDecision evaluateIncoming(IncomingCallContext context) {
            CallHandlingDecision decision;
            if (!isAvailable()) {
                decision = ringOwner("emulator_transport_unavailable", false);
            } else {
                SharedPreferences values = preferences();
                decision = new CallPolicyEngine(
                        values.getString(KEY_ANSWER_MODE, CallPolicyEngine.MODE_OFF),
                        10_000L,
                        values.getString(KEY_DELAY_MODE, AnswerDelayPolicy.DEFAULT_MODE),
                        values.getBoolean(KEY_PROCESSING_ENABLED, false))
                        .evaluate(context);
            }
            audit("decision:" + decision.answerDelayMillis + ":" + decision.action
                    + ":" + decision.reason);
            return decision;
        }

        @Override
        public void setTelecomCallPresent(IBinder token, String callId, boolean present) {
            audit("present:" + present);
        }

        @Override
        public void onCallAnswered(
                String callId, boolean answeredByAi, boolean processingAllowed) {
            audit("answered:" + (answeredByAi ? "ai" : "owner")
                    + ":" + processingAllowed);
        }

        @Override
        public void onCallAnsweredForDevelopmentTest(
                String callId, boolean processingAllowed) {
            audit("answered:development:" + processingAllowed);
        }

        @Override
        public void onCallResumed(
                String callId,
                boolean aiHandling,
                boolean processingAllowed,
                boolean knownContact) {
            audit("resumed:" + (aiHandling ? "ai" : "owner"));
        }

        @Override
        public void onEmergencyCallDetected(String callId) {
            audit("emergency");
        }

        @Override
        public boolean takeOverCall(String callId) {
            audit("takeover");
            return true;
        }

        @Override
        public void deleteCallHistory(String callId) {
            audit("history_deleted");
        }

        @Override
        public void onCallEnded(String callId, int disconnectCause) {
            audit("ended:" + disconnectCause);
        }

        @Override
        public void registerListener(ICallIntelligenceListener listener) {
            if (listener != null) listeners.register(listener);
        }

        @Override
        public void unregisterListener(ICallIntelligenceListener listener) {
            if (listener != null) listeners.unregister(listener);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        synchronized (EmulatorCallAssistantService.class) {
            activeInstance = this;
        }
    }

    @Override
    public void onDestroy() {
        synchronized (EmulatorCallAssistantService.class) {
            if (activeInstance == this) activeInstance = null;
        }
        listeners.kill();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (!EmulatorGuard.isEmulator()
                || !"com.aios.call.CALL_INTELLIGENCE_SERVICE".equals(intent.getAction())) {
            return null;
        }
        return binder;
    }

    static void notifyAvailabilityChanged() {
        EmulatorCallAssistantService service;
        synchronized (EmulatorCallAssistantService.class) {
            service = activeInstance;
        }
        if (service == null) return;
        int count = service.listeners.beginBroadcast();
        try {
            for (int index = 0; index < count; index++) {
                try {
                    service.listeners.getBroadcastItem(index).onServiceStatus(
                            "availability", 0, "speech_synthesis_emulator_fixture");
                } catch (Exception ignored) {
                    // The production client will reconnect or reload on its own.
                }
            }
        } finally {
            service.listeners.finishBroadcast();
        }
    }

    private CallAssistantPolicy readPolicy() {
        SharedPreferences values = preferences();
        CallAssistantPolicy policy = new CallAssistantPolicy();
        policy.answerMode = values.getString(KEY_ANSWER_MODE, CallPolicyEngine.MODE_OFF);
        policy.answerDelayMode = values.getString(KEY_DELAY_MODE, AnswerDelayPolicy.DEFAULT_MODE);
        policy.missedDelayMillis = 10_000L;
        policy.processingEnabled = values.getBoolean(KEY_PROCESSING_ENABLED, false);
        policy.callerHistoryEnabled = false;
        policy.messageHistoryEnabled = false;
        policy.callHistoryEnabled = false;
        policy.photoHistoryEnabled = false;
        policy.excludedCallerHistoryAddressHashes = new String[0];
        policy.automaticAnswerAvailable = isAvailable();
        policy.automaticAnswerUnavailableReason = policy.automaticAnswerAvailable
                ? "" : "emulator_transport_unavailable";
        return policy;
    }

    private boolean isAvailable() {
        return EmulatorGuard.isEmulator() && preferences().getBoolean(KEY_AVAILABLE, false);
    }

    private SharedPreferences preferences() {
        return getSharedPreferences(PREFERENCES, MODE_PRIVATE);
    }

    private static CallHandlingDecision ringOwner(String reason, boolean processingAllowed) {
        CallHandlingDecision value = new CallHandlingDecision();
        value.action = CallHandlingDecision.ACTION_RING_OWNER;
        value.answerDelayMillis = 0L;
        value.aiMayAnswer = false;
        value.processingAllowed = processingAllowed;
        value.reason = reason;
        return value;
    }

    private void audit(String event) {
        String line = SystemClock.elapsedRealtime() + ":" + event + "\n";
        synchronized (AUDIT_LOCK) {
            try (FileOutputStream output = new FileOutputStream(
                    new File(getCacheDir(), AUDIT_FILE), true)) {
                output.write(line.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            } catch (IOException error) {
                throw new IllegalStateException("fixture audit write failed", error);
            }
        }
    }
}
