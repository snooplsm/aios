package com.aios.call;

import com.aios.call.CallHandlingDecision;
import com.aios.call.CallAssistantPolicy;
import com.aios.call.ICallIntelligenceListener;
import com.aios.call.IncomingCallContext;

interface IAiosCallIntelligence {
    CallAssistantPolicy getPolicy();

    CallAssistantPolicy updatePolicy(in CallAssistantPolicy requested);

    CallHandlingDecision evaluateIncoming(in IncomingCallContext context);

    void onCallAnswered(String callId, boolean answeredByAi, boolean processingAllowed);

    void onCallEnded(String callId, int disconnectCause);

    void registerListener(in ICallIntelligenceListener listener);

    void unregisterListener(in ICallIntelligenceListener listener);
}
