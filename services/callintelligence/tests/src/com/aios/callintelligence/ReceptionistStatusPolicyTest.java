package com.aios.callintelligence;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ReceptionistStatusPolicyTest {
    @Test
    public void brokerRecoveryKeepsTheAssistantTurnOccupied() {
        assertFalse(ReceptionistStatusPolicy.completesAssistantOperation(
                "call-1", "receptionist_broker_recovering"));
    }

    @Test
    public void terminalFailureReleasesTheAssistantTurn() {
        assertTrue(ReceptionistStatusPolicy.completesAssistantOperation(
                "call-1", "receptionist_timeout"));
        assertTrue(ReceptionistStatusPolicy.completesAssistantOperation(
                "call-1", "receptionist_invalid_result"));
    }

    @Test
    public void availabilityEventsNeverCompleteACallTurn() {
        assertFalse(ReceptionistStatusPolicy.completesAssistantOperation(
                "availability", "receptionist_unavailable"));
        assertFalse(ReceptionistStatusPolicy.completesAssistantOperation(
                null, "receptionist_timeout"));
        assertFalse(ReceptionistStatusPolicy.completesAssistantOperation(
                "call-1", null));
    }
}
