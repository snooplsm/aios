package com.aios.call;

import android.os.IBinder;
import com.aios.call.CallHandlingDecision;
import com.aios.call.CallAssistantPolicy;
import com.aios.call.ICallIntelligenceListener;
import com.aios.call.IncomingCallContext;

interface IAiosCallIntelligence {
    CallAssistantPolicy getPolicy();

    CallAssistantPolicy updatePolicy(in CallAssistantPolicy requested);

    CallHandlingDecision evaluateIncoming(in IncomingCallContext context);

    /**
     * Assert opaque Telecom call presence independently of whether AI processing
     * is enabled. All calls owned by a token are removed if its process dies.
     * Answer, takeover, and terminal operations are accepted only from the UID
     * that owns the corresponding live call/session.
     */
    void setTelecomCallPresent(
        in IBinder lifecycleToken,
        String callId,
        boolean present
    );

    void onCallAnswered(String callId, boolean answeredByAi, boolean processingAllowed);

    /**
     * Userdebug-only manual AI answer. The service rejects this unless the
     * explicit development caller-uplink opt-in is active. Automatic answering
     * never uses this path.
     */
    void onCallAnsweredForDevelopmentTest(String callId, boolean processingAllowed);

    /** Restore optional processing after this service was rebound; never replay a greeting. */
    void onCallResumed(
        String callId,
        boolean aiHandling,
        boolean processingAllowed,
        boolean knownContact
    );

    /** Immediately stop and erase optional AI processing for an emergency call. */
    void onEmergencyCallDetected(String callId);

    /** Stop receptionist speech/replies while preserving capture and transcription. */
    boolean takeOverCall(String callId);

    void onCallEnded(String callId, int disconnectCause);

    void registerListener(in ICallIntelligenceListener listener);

    void unregisterListener(in ICallIntelligenceListener listener);
}
