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
    }

    @Test
    public void invalidRevisionsFailClosed() {
        try {
            RevisionGate.accepts(0L, 0L, 0L);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
