package com.aios.callintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

public final class AssistantHandlingTrackerTest {
    @Test
    public void aiHandledCallPublishesInitialStateAndOneTakeover() {
        AtomicLong now = new AtomicLong(2_000L);
        AssistantHandlingTracker tracker = new AssistantHandlingTracker(
                true, now::incrementAndGet);

        AssistantHandlingTracker.Update initial = tracker.initial();
        AssistantHandlingTracker.Update takeover = tracker.takeOver();

        assertTrue(initial.aiHandling);
        assertEquals(1L, initial.revision);
        assertFalse(takeover.aiHandling);
        assertEquals(2L, takeover.revision);
        assertEquals(2_002L, takeover.observedAtEpochMillis);
        assertFalse(tracker.isAiHandling());
        assertSame(takeover, tracker.current());
        assertNull(tracker.takeOver());
    }

    @Test
    public void ownerHandledCallCannotEnterAiModeThroughTakeoverApi() {
        AssistantHandlingTracker tracker = new AssistantHandlingTracker(false, () -> 3_000L);

        AssistantHandlingTracker.Update initial = tracker.initial();

        assertFalse(initial.aiHandling);
        assertEquals(1L, initial.revision);
        assertNull(tracker.takeOver());
    }

    @Test
    public void repeatedInitialReadDoesNotConsumeRevision() {
        AssistantHandlingTracker tracker = new AssistantHandlingTracker(true, () -> 4_000L);
        AssistantHandlingTracker.Update first = tracker.initial();

        assertSame(first, tracker.initial());
        assertEquals(1L, tracker.current().revision);
    }
}
