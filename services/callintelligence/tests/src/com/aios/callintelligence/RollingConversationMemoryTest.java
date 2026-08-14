package com.aios.callintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RollingConversationMemoryTest {
    @Test
    public void livePartialIsReplaceableAndNeverCompacted() {
        RollingConversationMemory memory = new RollingConversationMemory();
        assertTrue(memory.observePartial("en", "I need a", 1L));
        assertTrue(memory.observePartial("en", "I need a plumber", 2L));
        assertFalse(memory.observePartial("en", "stale", 1L));

        RollingConversationMemory.PromptSnapshot partial = memory.promptSnapshot();
        assertEquals("caller[en][partial]: I need a plumber", partial.livePartial);
        assertEquals("", partial.recentExactTurns);
        assertNull(memory.prepareCompaction());

        assertTrue(memory.appendFinal("caller", "en", "I need a plumber tomorrow"));
        RollingConversationMemory.PromptSnapshot committed = memory.promptSnapshot();
        assertEquals("", committed.livePartial);
        assertEquals(
                "caller[en]: I need a plumber tomorrow\n",
                committed.recentExactTurns);
    }

    @Test
    public void compactionNamesExactPrefixAndKeepsNewerTurnsVerbatim() {
        RollingConversationMemory memory = new RollingConversationMemory();
        for (int index = 0; index < 10; index++) {
            assertTrue(memory.appendFinal(
                    index % 2 == 0 ? "caller" : "assistant",
                    index % 3 == 0 ? "es" : "en",
                    "turn " + index));
        }

        RollingConversationMemory.CompactionInput input = memory.prepareCompaction();
        assertNotNull(input);
        assertEquals(1L, input.firstTurnId);
        assertEquals(2L, input.lastTurnId);
        assertTrue(input.finalizedPrefix.contains("turn 0"));
        assertTrue(input.finalizedPrefix.contains("turn 1"));

        assertTrue(memory.appendFinal("caller", "en", "arrived during compaction"));
        assertTrue(memory.applyCompaction(
                input, "{\"intent\":\"plumbing\",\"open_questions\":[]}"));

        RollingConversationMemory.PromptSnapshot snapshot = memory.promptSnapshot();
        assertEquals(1L, snapshot.summaryRevision);
        assertEquals(2L, snapshot.summaryThroughTurnId);
        assertEquals(
                "{\"intent\":\"plumbing\",\"open_questions\":[]}",
                snapshot.structuredSummaryJson);
        assertTrue(snapshot.recentExactTurns.contains("arrived during compaction"));
        assertFalse(snapshot.recentExactTurns.contains("turn 0"));
        assertFalse(snapshot.recentExactTurns.contains("turn 1"));
    }

    @Test
    public void staleOrDuplicateCompactionCannotReplaceNewerSummary() {
        RollingConversationMemory memory = new RollingConversationMemory();
        for (int index = 0; index < 10; index++) {
            memory.appendFinal("caller", "en", "turn " + index);
        }
        RollingConversationMemory.CompactionInput first = memory.prepareCompaction();
        assertNotNull(first);
        assertTrue(memory.applyCompaction(first, "{\"revision\":1}"));
        assertFalse(memory.applyCompaction(first, "{\"revision\":999}"));
        assertEquals(
                "{\"revision\":1}", memory.promptSnapshot().structuredSummaryJson);
    }

    @Test
    public void compactionInputFitsTheConfiguredModelContextBudget() {
        RollingConversationMemory memory = new RollingConversationMemory();
        for (int index = 0; index < 14; index++) {
            memory.appendFinal("caller", "es", index + ":" + "x".repeat(1_500));
        }

        RollingConversationMemory.CompactionInput input = memory.prepareCompaction();

        assertNotNull(input);
        assertTrue(input.finalizedPrefix.length()
                <= RollingConversationMemory.MAX_COMPACTION_INPUT_CHARS);
        assertTrue(memory.promptSnapshot().recentExactTurns.length()
                <= RollingConversationMemory.MAX_RECENT_CHARS);
    }

    @Test
    public void promptAndFallbackStorageRemainBounded() {
        RollingConversationMemory memory = new RollingConversationMemory();
        for (int index = 0; index < 100; index++) {
            memory.appendFinal("caller", "en", index + ":" + "x".repeat(2_000));
        }
        RollingConversationMemory.PromptSnapshot snapshot = memory.promptSnapshot();
        assertTrue(snapshot.recentExactTurns.length()
                <= RollingConversationMemory.MAX_RECENT_CHARS);
        // Once deterministic fallback eviction has dropped an unsummarized
        // prefix, semantic compaction must not pretend it consumed that source.
        assertNull(memory.prepareCompaction());
    }
}
