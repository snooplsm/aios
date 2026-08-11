package com.aios.callintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CallTranscriptRevisionClockTest {
    @Test
    public void firstStreamMapsProviderSequenceToCallRevision() {
        CallTranscriptRevisionClock clock = new CallTranscriptRevisionClock();
        Object stream = new Object();

        assertTrue(clock.activate(stream));
        assertEquals(0L, clock.advance(stream, 0L));
        assertEquals(1L, clock.advance(stream, 1L));
        assertEquals(2L, clock.advance(stream, 5L));
    }

    @Test
    public void duplicateAndRegressedProviderSequencesFailClosed() {
        CallTranscriptRevisionClock clock = new CallTranscriptRevisionClock();
        Object stream = new Object();
        clock.activate(stream);
        assertEquals(0L, clock.advance(stream, 4L));

        assertEquals(CallTranscriptRevisionClock.UNACCEPTED, clock.advance(stream, 4L));
        assertEquals(CallTranscriptRevisionClock.UNACCEPTED, clock.advance(stream, 3L));
        assertEquals(CallTranscriptRevisionClock.UNACCEPTED, clock.advance(stream, -1L));
        assertEquals(1L, clock.advance(stream, 6L));
    }

    @Test
    public void replacementStreamCanRestartAtZeroWithoutRevisionCollision() {
        CallTranscriptRevisionClock clock = new CallTranscriptRevisionClock();
        Object first = new Object();
        Object replacement = new Object();
        clock.activate(first);
        assertEquals(0L, clock.advance(first, 0L));
        assertEquals(1L, clock.advance(first, 1L));

        assertTrue(clock.activate(replacement));
        assertEquals(2L, clock.advance(replacement, 0L));
        assertEquals(3L, clock.advance(replacement, 1L));
        assertEquals(CallTranscriptRevisionClock.UNACCEPTED, clock.advance(first, 2L));
    }

    @Test
    public void detachmentRejectsLateCallbacksUntilReplacementActivates() {
        CallTranscriptRevisionClock clock = new CallTranscriptRevisionClock();
        Object first = new Object();
        Object replacement = new Object();
        clock.activate(first);
        assertEquals(0L, clock.advance(first, 0L));

        assertTrue(clock.deactivate(first));
        assertEquals(CallTranscriptRevisionClock.UNACCEPTED, clock.advance(first, 1L));
        assertFalse(clock.deactivate(first));
        assertFalse(clock.activate(null));

        assertTrue(clock.activate(replacement));
        assertEquals(1L, clock.advance(replacement, 0L));
    }

    @Test
    public void reactivatingSameStreamDoesNotResetItsSourceGate() {
        CallTranscriptRevisionClock clock = new CallTranscriptRevisionClock();
        Object stream = new Object();
        clock.activate(stream);
        assertEquals(0L, clock.advance(stream, 2L));

        assertTrue(clock.activate(stream));
        assertEquals(CallTranscriptRevisionClock.UNACCEPTED, clock.advance(stream, 2L));
        assertEquals(1L, clock.advance(stream, 3L));
    }

    @Test
    public void recoveredRevisionPreservesFinalizedClassifierContext() {
        CallTranscriptRevisionClock clock = new CallTranscriptRevisionClock();
        IncrementalCallerTranscript transcript = new IncrementalCallerTranscript(512);
        Object first = new Object();
        Object replacement = new Object();
        clock.activate(first);
        long firstRevision = clock.advance(first, 0L);
        assertTrue(transcript.observe("en", "First finalized turn", true, firstRevision));

        clock.deactivate(first);
        clock.activate(replacement);
        long recoveredRevision = clock.advance(replacement, 0L);
        assertTrue(transcript.observe(
                "es", "Nueva frase", false, recoveredRevision));

        assertEquals(
                "[en][final] First finalized turn\n[es][partial] Nueva frase\n",
                transcript.snapshot().text);
        assertEquals(firstRevision + 1L, recoveredRevision);
    }
}
