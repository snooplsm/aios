package com.aios.callintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class RequiredCaptureGateTest {
    @Test
    public void bothDirectionsAreRequired() {
        RequiredCaptureGate gate = new RequiredCaptureGate();
        gate.markReady(RequiredCaptureGate.DOWNLINK);

        assertEquals("uplink_first_pcm_timeout", gate.await(1L));
    }

    @Test
    public void bothDirectionsReleaseTheGate() {
        RequiredCaptureGate gate = new RequiredCaptureGate();
        gate.markReady(RequiredCaptureGate.UPLINK);
        gate.markReady(RequiredCaptureGate.DOWNLINK);

        assertNull(gate.await(1L));
    }

    @Test
    public void firstFailureIsPreserved() {
        RequiredCaptureGate gate = new RequiredCaptureGate();
        gate.markFailure(RequiredCaptureGate.DOWNLINK, "downlink_read_failed");
        gate.markFailure(RequiredCaptureGate.UPLINK, "uplink_read_failed");

        assertEquals("downlink_read_failed", gate.await(1L));
    }

    @Test
    public void unknownDirectionFailsClosed() {
        RequiredCaptureGate gate = new RequiredCaptureGate();
        gate.markReady("mixed");

        assertEquals("unknown_capture_direction", gate.await(1L));
    }
}
