package com.aios.callintelligence;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SpeechSynthesisStatusPolicyTest {
    @Test
    public void brokerLossAndProviderErrorsStopCallerAudio() {
        assertTrue(SpeechSynthesisStatusPolicy.terminatesCallerAudio(
                "speech_synthesis_broker_disconnected"));
        assertTrue(SpeechSynthesisStatusPolicy.terminatesCallerAudio(
                "speech_synthesis_error_7"));
    }

    @Test
    public void completionAllowsThePcmPipeToDrain() {
        assertFalse(SpeechSynthesisStatusPolicy.terminatesCallerAudio(
                "speech_synthesis_complete"));
    }

    @Test
    public void availabilityAndMalformedStatusesAreNotCallTerminal() {
        assertFalse(SpeechSynthesisStatusPolicy.terminatesCallerAudio(
                "speech_synthesis_ready"));
        assertFalse(SpeechSynthesisStatusPolicy.terminatesCallerAudio(
                "speech_synthesis_preparing"));
        assertFalse(SpeechSynthesisStatusPolicy.terminatesCallerAudio(
                "speech_synthesis_prepared"));
        assertFalse(SpeechSynthesisStatusPolicy.terminatesCallerAudio(null));
    }
}
