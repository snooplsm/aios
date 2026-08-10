package com.aios.contextintelligence;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public final class RevisionGateTest {
    @Test
    public void onlyNewerRevisionsAreAccepted() {
        assertTrue(RevisionGate.accepts(3L, 2L, 1L));
        assertFalse(RevisionGate.accepts(2L, 2L, 1L));
        assertFalse(RevisionGate.accepts(2L, 0L, 2L));
        assertFalse(RevisionGate.accepts(4L, 0L, 0L, 4L));
        assertTrue(RevisionGate.accepts(5L, 0L, 0L, 4L));
    }

    @Test
    public void invalidRevisionsFailClosed() {
        try {
            RevisionGate.accepts(0L, 0L, 0L);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
        try {
            RevisionGate.accepts(1L, 0L, 0L, -1L);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
