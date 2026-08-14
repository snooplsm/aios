package com.aios.callintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public final class TranscriptContextRecoveryTest {
    @Test
    public void admitsOnlyFinalBilingualCallerAndAssistantTurns() {
        TranscriptContextRecovery recovery = new TranscriptContextRecovery();

        assertFalse(recovery.accept("downlink", "en", "live partial", false));
        assertFalse(recovery.accept("uplink", "en", "owner audio", true));
        assertFalse(recovery.accept("downlink", "fr", "bonjour", true));
        assertFalse(recovery.accept("assistant", "es", "draft", false));
        assertTrue(recovery.accept("downlink", "en", "  Need\nservice  ", true));
        assertTrue(recovery.accept("assistant", "es", "Claro, le ayudo.", true));

        List<TranscriptContextRecovery.Turn> turns = recovery.snapshot();
        assertEquals(2, turns.size());
        assertEquals("caller", turns.get(0).role);
        assertEquals("en", turns.get(0).language);
        assertEquals("Need service", turns.get(0).text);
        assertEquals("assistant", turns.get(1).role);
        assertEquals("es", turns.get(1).language);
    }

    @Test
    public void recoveryKeepsTheNewestBoundedExactTail() {
        TranscriptContextRecovery recovery = new TranscriptContextRecovery();
        for (int index = 0; index < 100; index++) {
            assertTrue(recovery.accept(
                    "downlink", "en", index + ":" + "x".repeat(2_100), true));
        }

        List<TranscriptContextRecovery.Turn> turns = recovery.snapshot();
        int characters = turns.stream()
                .mapToInt(TranscriptContextRecovery.Turn::retainedCharacters)
                .sum();
        assertTrue(turns.size() <= TranscriptContextRecovery.MAX_RECOVERED_TURNS);
        assertTrue(characters <= TranscriptContextRecovery.MAX_RECOVERED_CHARS);
        assertFalse(turns.get(0).text.startsWith("0:"));
        assertTrue(turns.get(turns.size() - 1).text.startsWith("99:"));
        assertTrue(turns.get(turns.size() - 1).text.length()
                <= RollingConversationMemory.MAX_TURN_CHARS);
    }

    @Test
    public void snapshotsCannotMutateTheRecoveryBuffer() {
        TranscriptContextRecovery recovery = new TranscriptContextRecovery();
        recovery.accept("downlink", "en", "first", true);

        List<TranscriptContextRecovery.Turn> first = recovery.snapshot();
        first.clear();

        assertEquals(1, recovery.snapshot().size());
    }
}
