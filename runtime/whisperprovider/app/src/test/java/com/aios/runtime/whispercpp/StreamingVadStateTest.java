package com.aios.runtime.whispercpp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class StreamingVadStateTest {
    @Test
    public void leadingSilenceIsIgnoredUntilSpeechStarts() {
        StreamingVadState state = new StreamingVadState(6);

        assertEquals(StreamingVadState.Event.IGNORED, state.accept(false));
        assertFalse(state.isActive());
        assertEquals(StreamingVadState.Event.STARTED, state.accept(true));
        assertTrue(state.isActive());
    }

    @Test
    public void exactlySixHundredMillisecondsOfSilenceEndsTheTurn() {
        StreamingVadState state = new StreamingVadState(6);
        state.accept(true);

        for (int frame = 0; frame < 5; frame++) {
            assertEquals(StreamingVadState.Event.CONTINUED, state.accept(false));
            assertTrue(state.isActive());
        }
        assertEquals(StreamingVadState.Event.ENDED, state.accept(false));
        assertFalse(state.isActive());
        assertEquals(StreamingVadState.Event.IGNORED, state.accept(false));
    }

    @Test
    public void resumedSpeechResetsTheSilenceRun() {
        StreamingVadState state = new StreamingVadState(3);
        state.accept(true);
        state.accept(false);
        state.accept(false);

        assertEquals(StreamingVadState.Event.CONTINUED, state.accept(true));
        assertTrue(state.isActive());
        assertEquals(StreamingVadState.Event.CONTINUED, state.accept(false));
        assertEquals(StreamingVadState.Event.CONTINUED, state.accept(false));
        assertEquals(StreamingVadState.Event.ENDED, state.accept(false));
    }

    @Test
    public void nextTurnStartsCleanlyAfterEndpoint() {
        StreamingVadState state = new StreamingVadState(1);
        assertEquals(StreamingVadState.Event.STARTED, state.accept(true));
        assertEquals(StreamingVadState.Event.ENDED, state.accept(false));
        assertEquals(StreamingVadState.Event.STARTED, state.accept(true));
    }

    @Test
    public void invalidEndpointConfigurationFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> new StreamingVadState(0));
        assertThrows(IllegalArgumentException.class, () -> new StreamingVadState(-1));
    }
}
