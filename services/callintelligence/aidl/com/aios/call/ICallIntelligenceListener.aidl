package com.aios.call;

import com.aios.call.CallAssistantState;
import com.aios.call.CallRiskAssessment;
import com.aios.call.TranscriptSegment;

oneway interface ICallIntelligenceListener {
    void onTranscript(in TranscriptSegment segment);
    void onRiskChanged(in CallRiskAssessment assessment);
    void onAssistantStateChanged(in CallAssistantState state);
    void onServiceStatus(String callId, int status, String detail);
}
