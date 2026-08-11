package com.aios.callintelligence;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AssistantAudioIdentityGateTest {
    @Test
    public void ttsFailureWinsOnceAndRejectsLateUplinkCompletion() {
        AssistantAudioIdentityGate gate = new AssistantAudioIdentityGate();
        Object speech = new Object();
        Object uplink = new Object();

        assertTrue(gate.attach(speech, uplink));
        assertTrue(gate.consumeSpeech(speech));
        assertFalse(gate.consumeUplink(uplink));
    }

    @Test
    public void uplinkCompletionWinsOnceAndRejectsLateTtsFailure() {
        AssistantAudioIdentityGate gate = new AssistantAudioIdentityGate();
        Object speech = new Object();
        Object uplink = new Object();

        assertTrue(gate.attach(speech, uplink));
        assertTrue(gate.consumeUplink(uplink));
        assertFalse(gate.consumeSpeech(speech));
    }

    @Test
    public void staleIdentitiesCannotDisplaceCurrentAudio() {
        AssistantAudioIdentityGate gate = new AssistantAudioIdentityGate();
        Object speech = new Object();
        Object uplink = new Object();

        assertTrue(gate.attach(speech, uplink));
        assertFalse(gate.consumeSpeech(new Object()));
        assertFalse(gate.consumeUplink(new Object()));
        assertTrue(gate.acceptsUplink(uplink));
        assertFalse(gate.attach(new Object(), new Object()));
    }

    @Test
    public void explicitClearRejectsBothLateTerminals() {
        AssistantAudioIdentityGate gate = new AssistantAudioIdentityGate();
        Object speech = new Object();
        Object uplink = new Object();
        assertTrue(gate.attach(speech, uplink));

        gate.clear();

        assertFalse(gate.consumeSpeech(speech));
        assertFalse(gate.consumeUplink(uplink));
    }

    @Test
    public void providerStartRequiresTheExactAttachedPairAndHappensOnce() {
        AssistantAudioIdentityGate gate = new AssistantAudioIdentityGate();
        Object speech = new Object();
        Object uplink = new Object();

        assertFalse(gate.begin(speech, uplink));
        assertTrue(gate.attach(speech, uplink));
        assertFalse(gate.begin(new Object(), uplink));
        assertFalse(gate.begin(speech, new Object()));
        assertTrue(gate.begin(speech, uplink));
        assertFalse(gate.begin(speech, uplink));
    }
}
