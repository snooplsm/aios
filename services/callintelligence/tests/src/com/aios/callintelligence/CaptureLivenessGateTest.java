package com.aios.callintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class CaptureLivenessGateTest {
    @Test
    public void startupStopIsLeftToTheStartupGate() {
        CaptureLivenessGate gate = new CaptureLivenessGate();

        assertNull(gate.onDirectionStopped("downlink", false, "downlink_start_failed"));
    }

    @Test
    public void firstPostPcmLossWins() {
        CaptureLivenessGate gate = new CaptureLivenessGate();

        CaptureLivenessGate.Failure failure =
                gate.onDirectionStopped("downlink", true, "downlink_read_failed");

        assertNotNull(failure);
        assertEquals("downlink", failure.direction);
        assertEquals("downlink_read_failed", failure.reason);
        assertNull(gate.onDirectionStopped("uplink", true, "uplink_read_failed"));
    }

    @Test
    public void intentionalCloseSuppressesLoss() {
        CaptureLivenessGate gate = new CaptureLivenessGate();
        gate.close();

        assertNull(gate.onDirectionStopped("downlink", true, "downlink_read_failed"));
    }

    @Test
    public void emptyAndUnknownFailureDataIsBounded() {
        CaptureLivenessGate gate = new CaptureLivenessGate();

        CaptureLivenessGate.Failure failure =
                gate.onDirectionStopped("mixed", true, "");

        assertNotNull(failure);
        assertEquals("unknown", failure.direction);
        assertEquals("unknown_capture_lost", failure.reason);
    }
}
