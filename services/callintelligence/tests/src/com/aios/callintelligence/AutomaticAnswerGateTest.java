package com.aios.callintelligence;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AutomaticAnswerGateTest {
    @Test
    public void policyAndTransportMustBothAllowAnswer() {
        assertTrue(AutomaticAnswerGate.mayAnswer(true, true));
        assertFalse(AutomaticAnswerGate.mayAnswer(true, false));
        assertFalse(AutomaticAnswerGate.mayAnswer(false, true));
        assertFalse(AutomaticAnswerGate.mayAnswer(false, false));
    }
}
