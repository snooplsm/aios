package com.aios.callintelligence;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CallerUplinkAdmissionTest {
    @Test
    public void explicitDebugOptInUnlocksOnlyManualTesting() {
        assertTrue(CallerUplinkAdmission.manualAnswerAllowed(false, true, true));
        assertTrue(CallerUplinkAdmission.developmentTestActive(false, true, true));
        assertFalse(CallerUplinkAdmission.automaticAnswerAllowed(false));
    }

    @Test
    public void productionAndMissingOptInFailClosed() {
        assertFalse(CallerUplinkAdmission.manualAnswerAllowed(false, false, true));
        assertFalse(CallerUplinkAdmission.manualAnswerAllowed(false, true, false));
        assertFalse(CallerUplinkAdmission.developmentTestActive(false, false, true));
        assertFalse(CallerUplinkAdmission.developmentTestActive(false, true, false));
    }

    @Test
    public void releaseValidationSupersedesDevelopmentMode() {
        assertTrue(CallerUplinkAdmission.manualAnswerAllowed(true, false, false));
        assertTrue(CallerUplinkAdmission.automaticAnswerAllowed(true));
        assertFalse(CallerUplinkAdmission.developmentTestActive(true, true, true));
    }
}
