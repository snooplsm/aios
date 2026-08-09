package com.aios.call;

import com.aios.call.TranscriptSegment;

oneway interface ICallIntelligenceListener {
    void onTranscript(in TranscriptSegment segment);
    void onRiskChanged(String callId, int riskScore, String reason);
    void onServiceStatus(String callId, int status, String detail);
}
