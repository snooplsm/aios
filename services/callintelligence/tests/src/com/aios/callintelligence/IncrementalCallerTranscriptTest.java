package com.aios.callintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class IncrementalCallerTranscriptTest {
    @Test
    public void partialRevisionReplacesWordsInsteadOfDuplicatingThem() {
        IncrementalCallerTranscript transcript = new IncrementalCallerTranscript(512);

        assertTrue(transcript.observe("en", "I need a plumbing", false, 0L));
        assertTrue(transcript.observe("en", "I need a plumbing estimate", false, 1L));
        IncrementalCallerTranscript.Snapshot snapshot = transcript.snapshot();

        assertEquals("[en][partial] I need a plumbing estimate\n", snapshot.text);
        assertEquals(1L, snapshot.revision);
        assertFalse(snapshot.isFinal);
    }

    @Test
    public void finalizedTurnBecomesHistoryForTheNextPartial() {
        IncrementalCallerTranscript transcript = new IncrementalCallerTranscript(512);
        transcript.observe("es", "Necesito una cita", false, 0L);
        transcript.observe("es", "Necesito una cita mañana", true, 1L);
        transcript.observe("en", "The address is", false, 2L);

        IncrementalCallerTranscript.Snapshot snapshot = transcript.snapshot();

        assertEquals(
                "[es][final] Necesito una cita mañana\n"
                        + "[en][partial] The address is\n",
                snapshot.text);
        assertEquals("en", snapshot.language);
        assertFalse(snapshot.isFinal);
    }

    @Test
    public void staleAndInvalidRevisionsDoNotReplaceCurrentWords() {
        IncrementalCallerTranscript transcript = new IncrementalCallerTranscript(256);
        assertTrue(transcript.observe("en", "Current words", false, 0L));

        assertFalse(transcript.observe("en", "Older words", false, 0L));
        assertFalse(transcript.observe("fr", "Nouveaux mots", false, 1L));
        assertFalse(transcript.observe("en", "  ", true, 1L));

        assertEquals("[en][partial] Current words\n", transcript.snapshot().text);
        assertEquals(0L, transcript.snapshot().revision);
    }

    @Test
    public void snapshotKeepsTheNewestBoundedContext() {
        IncrementalCallerTranscript transcript = new IncrementalCallerTranscript(64);
        transcript.observe(
                "en", "an old finalized sentence that should be trimmed", true, 0L);
        transcript.observe("en", "newest partial words", false, 1L);

        IncrementalCallerTranscript.Snapshot snapshot = transcript.snapshot();

        assertTrue(snapshot.text.length() <= 64);
        assertTrue(snapshot.text.endsWith("[en][partial] newest partial words\n"));
    }
}
