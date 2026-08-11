package com.aios.callintelligence;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CallContextAccumulatorTest {
    @Test
    public void retainsOnlyFinalBilingualSegmentsAndSafeAssessments() {
        CallContextAccumulator value = new CallContextAccumulator();
        value.appendTranscript("downlink", "en", "partial", false);
        value.appendTranscript("unknown", "en", "wrong direction", true);
        value.appendTranscript("downlink", "fr", "wrong language", true);
        value.appendTranscript("downlink", "en", "Need an estimate tomorrow", true);
        value.appendTranscript("uplink", "es", "Le devolveremos la llamada", true);
        value.appendAssistantReply("en", "What time works best?");
        value.appendAssessment(80, "high_risk", "provisional_false_alarm");
        value.appendAssessment(20, "likely_legitimate", "known_contact");
        value.appendAssessment(101, "high_risk", "invalid_score");

        String result = value.finish(2);

        assertFalse(result.contains("partial"));
        assertFalse(result.contains("wrong direction"));
        assertFalse(result.contains("wrong language"));
        assertFalse(result.contains("invalid_score"));
        assertFalse(result.contains("provisional_false_alarm"));
        assertTrue(result.contains("downlink[en]: Need an estimate tomorrow"));
        assertTrue(result.contains("uplink[es]: Le devolveremos la llamada"));
        assertTrue(result.contains("assistant[en]: What time works best?"));
        assertTrue(result.contains("risk: 20 likely_legitimate known_contact"));
        assertTrue(result.endsWith("call_end: disconnect_cause=2"));
    }

    @Test
    public void evictsOldestTextWithinDocumentLimit() {
        CallContextAccumulator value = new CallContextAccumulator();
        for (int index = 0; index < 10; index++) {
            value.appendTranscript(
                    "downlink", "en", index + ":" + "x".repeat(1_020), true);
        }

        String result = value.finish(9);

        assertTrue(result.length() <= CallContextAccumulator.MAX_DOCUMENT_CHARS);
        assertFalse(result.contains("0:"));
        assertTrue(result.contains("9:"));
        assertTrue(result.endsWith("call_end: disconnect_cause=9"));
    }
}
