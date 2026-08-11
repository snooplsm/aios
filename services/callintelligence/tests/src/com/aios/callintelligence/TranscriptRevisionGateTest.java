package com.aios.callintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TranscriptRevisionGateTest {
    @Test
    public void onlyStrictlyNewerAsrSequencesAdvance() {
        TranscriptRevisionGate gate = new TranscriptRevisionGate();

        assertTrue(gate.advance(0L));
        assertFalse(gate.advance(0L));
        assertFalse(gate.advance(-1L));
        assertTrue(gate.advance(2L));
        assertFalse(gate.advance(1L));
        assertEquals(2L, gate.current());
    }

    @Test
    public void classifierResultMustMatchTheExactCurrentSequence() {
        TranscriptRevisionGate gate = new TranscriptRevisionGate();
        gate.advance(4L);

        assertTrue(gate.accepts(4L));
        assertFalse(gate.accepts(3L));
        gate.advance(5L);
        assertFalse(gate.accepts(4L));
        assertTrue(gate.accepts(5L));
    }

    @Test
    public void receptionistResultCanRemainExplicitlyUnbound() {
        TranscriptRevisionGate gate = new TranscriptRevisionGate();
        gate.advance(7L);

        assertTrue(gate.accepts(TranscriptRevisionGate.UNBOUND));
    }

    @Test
    public void streamReplacementInvalidatesEarlierClassifierRevision() {
        TranscriptRevisionGate gate = new TranscriptRevisionGate();
        gate.advance(7L);

        gate.invalidate();

        assertFalse(gate.accepts(7L));
        assertEquals(TranscriptRevisionGate.UNBOUND, gate.current());
        assertTrue(gate.advance(8L));
    }
}
