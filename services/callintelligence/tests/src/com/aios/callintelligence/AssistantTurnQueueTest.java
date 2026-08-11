package com.aios.callintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AssistantTurnQueueTest {
    @Test
    public void greetingSerializesFirstCallerTurn() {
        AssistantTurnQueue queue = new AssistantTurnQueue();

        assertTrue(queue.beginGreeting());
        assertNull(queue.offer("en", "I need an estimate"));
        AssistantTurnQueue.CallerTurn next = queue.complete();

        assertEquals("en", next.language);
        assertEquals("I need an estimate", next.text);
        assertTrue(queue.isBusy());
        assertNull(queue.complete());
        assertFalse(queue.isBusy());
    }

    @Test
    public void finalizedSegmentsCoalesceWhileAssistantIsBusy() {
        AssistantTurnQueue queue = new AssistantTurnQueue();
        assertTrue(queue.beginGreeting());
        assertNull(queue.offer("en", "first turn"));
        assertNull(queue.offer("es", "turno final"));

        AssistantTurnQueue.CallerTurn next = queue.complete();

        assertEquals("es", next.language);
        assertEquals("first turn turno final", next.text);
    }

    @Test
    public void pendingSpeechKeepsTheNewestBoundedContext() {
        AssistantTurnQueue queue = new AssistantTurnQueue();
        assertTrue(queue.beginGreeting());
        assertNull(queue.offer("en", "early " + "a".repeat(1_500)));
        assertNull(queue.offer("en", "latest " + "b".repeat(1_000)));

        AssistantTurnQueue.CallerTurn next = queue.complete();

        assertTrue(next.text.length() <= AssistantTurnQueue.MAX_PENDING_TEXT_CHARS);
        assertTrue(next.text.contains("latest"));
        assertTrue(next.text.endsWith("b".repeat(1_000)));
    }

    @Test
    public void closeDropsPendingAndRejectsNewWork() {
        AssistantTurnQueue queue = new AssistantTurnQueue();
        assertTrue(queue.beginGreeting());
        assertNull(queue.offer("en", "queued"));

        queue.close();

        assertFalse(queue.isBusy());
        assertNull(queue.complete());
        assertNull(queue.offer("en", "late"));
        assertFalse(queue.beginGreeting());
    }
}
