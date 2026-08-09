package com.aios.call;

import com.aios.call.CallRiskAssessment;
import com.aios.call.TranscriptSegment;

oneway interface ICallIntelligenceListener {
    void onTranscript(in TranscriptSegment segment);
    void onRiskChanged(in CallRiskAssessment assessment);
    void onServiceStatus(String callId, int status, String detail);
}
