package com.aios.mediaintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MediaJobRunGateTest {
    @Test
    public void overlappingStartCannotReplaceTheActiveRun() {
        MediaJobRunGate gate = new MediaJobRunGate();

        assertNotNull(gate.begin("delivery-1"));
        assertNull(gate.begin("delivery-2"));
    }

    @Test
    public void staleStopCannotCancelTheActiveRun() {
        MediaJobRunGate gate = new MediaJobRunGate();
        MediaJobRunGate.Token token = gate.begin("delivery-1");

        assertFalse(gate.stop("delivery-2"));
        assertEquals(MediaJobRunGate.Finish.COMPLETED, gate.finish(token));
    }

    @Test
    public void matchingStopSuppressesNormalCompletion() {
        MediaJobRunGate gate = new MediaJobRunGate();
        MediaJobRunGate.Token token = gate.begin(new String("delivery-1"));

        // Binder may unmarshal start and stop extras into distinct String objects.
        assertTrue(gate.stop(new String("delivery-1")));
        assertFalse(gate.stop("delivery-1"));
        assertEquals(MediaJobRunGate.Finish.STOPPED, gate.finish(token));
    }

    @Test
    public void oldFinishCannotClearAReplacementRun() {
        MediaJobRunGate gate = new MediaJobRunGate();
        MediaJobRunGate.Token old = gate.begin("delivery-1");
        assertEquals(MediaJobRunGate.Finish.COMPLETED, gate.finish(old));
        MediaJobRunGate.Token replacement = gate.begin("delivery-2");

        assertEquals(MediaJobRunGate.Finish.STALE, gate.finish(old));
        assertEquals(MediaJobRunGate.Finish.COMPLETED, gate.finish(replacement));
    }
}
