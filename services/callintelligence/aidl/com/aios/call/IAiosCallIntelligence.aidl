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

    /** Stop receptionist speech/replies while preserving capture and transcription. */
    boolean takeOverCall(String callId);

    void onCallEnded(String callId, int disconnectCause);

    void registerListener(in ICallIntelligenceListener listener);

    void unregisterListener(in ICallIntelligenceListener listener);
}
