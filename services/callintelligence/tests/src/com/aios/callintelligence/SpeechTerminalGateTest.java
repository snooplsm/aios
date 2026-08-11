package com.aios.callintelligence;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SpeechTerminalGateTest {
    @Test
    public void onlyTheFirstTerminalPathCanClaimSpeech() {
        SpeechTerminalGate gate = new SpeechTerminalGate();

        assertFalse(gate.isTerminal());
        assertTrue(gate.claim());
        assertTrue(gate.isTerminal());
        assertFalse(gate.claim());
    }

    @Test
    public void ownerCloseSuppressesEveryLaterProviderTerminal() {
        SpeechTerminalGate gate = new SpeechTerminalGate();

        assertTrue(gate.claim());
        assertFalse(gate.claim());
        assertFalse(gate.claim());
    }
}
