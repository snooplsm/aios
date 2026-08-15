package com.aios.callintelligence;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AssistantGreetingPrewarmPolicyTest {
    @Test
    public void completedPreparedGreetingStartsReceptionistPrewarm() {
        assertTrue(AssistantGreetingPrewarmPolicy.shouldPrewarmReceptionist(
                "call-1", "call-1:tts:preanswer:7", "speech_synthesis_complete"));
    }

    @Test
    public void ordinaryRepliesErrorsAndForeignRequestsDoNotPrewarm() {
        assertFalse(AssistantGreetingPrewarmPolicy.shouldPrewarmReceptionist(
                "call-1", "call-1:tts:8", "speech_synthesis_complete"));
        assertFalse(AssistantGreetingPrewarmPolicy.shouldPrewarmReceptionist(
                "call-1", "call-1:tts:preanswer:7", "speech_synthesis_error_7"));
        assertFalse(AssistantGreetingPrewarmPolicy.shouldPrewarmReceptionist(
                "call-1", "call-2:tts:preanswer:7", "speech_synthesis_complete"));
        assertFalse(AssistantGreetingPrewarmPolicy.shouldPrewarmReceptionist(
                null, "call-1:tts:preanswer:7", "speech_synthesis_complete"));
    }
}
