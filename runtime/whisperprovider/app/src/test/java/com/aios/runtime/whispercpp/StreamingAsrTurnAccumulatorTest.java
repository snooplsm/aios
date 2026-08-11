package com.aios.runtime.whispercpp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class StreamingAsrTurnAccumulatorTest {
    @Test
    public void partialsContainTheCompleteCurrentTurn() {
        StreamingAsrTurnAccumulator accumulator = new StreamingAsrTurnAccumulator();

        StreamingAsrTurnAccumulator.Emission first =
                accumulator.acceptDecoded("hello", "en", 0L, 2_000L, false);
        assertEquals("hello", first.text);
        assertEquals("en", first.language);
        assertEquals(0L, first.startMillis);
        assertEquals(2_000L, first.endMillis);
        assertFalse(first.finalChunk);

        StreamingAsrTurnAccumulator.Emission second =
                accumulator.acceptDecoded("mundo", "es", 2_000L, 4_000L, false);
        assertEquals("hello mundo", second.text);
        assertEquals("es", second.language);
        assertEquals(0L, second.startMillis);
        assertEquals(4_000L, second.endMillis);
        assertFalse(second.finalChunk);
    }

    @Test
    public void silenceEndpointFinalizesAndResetsTheTurn() {
        StreamingAsrTurnAccumulator accumulator = new StreamingAsrTurnAccumulator();
        accumulator.acceptDecoded("first turn", "en", 100L, 2_100L, false);

        StreamingAsrTurnAccumulator.Emission finished = accumulator.finishTurn();
        assertEquals("first turn", finished.text);
        assertEquals("en", finished.language);
        assertEquals(100L, finished.startMillis);
        assertEquals(2_100L, finished.endMillis);
        assertTrue(finished.finalChunk);
        assertNull(accumulator.finishTurn());

        StreamingAsrTurnAccumulator.Emission next =
                accumulator.acceptDecoded("second", "en", 5_000L, 7_000L, false);
        assertEquals("second", next.text);
        assertEquals(5_000L, next.startMillis);
    }

    @Test
    public void finalDecodedResidualIsIncludedBeforeReset() {
        StreamingAsrTurnAccumulator accumulator = new StreamingAsrTurnAccumulator();
        accumulator.acceptDecoded("please", "en", 0L, 2_000L, false);

        StreamingAsrTurnAccumulator.Emission finished =
                accumulator.acceptDecoded("call back", "en", 2_000L, 2_800L, true);
        assertEquals("please call back", finished.text);
        assertEquals(0L, finished.startMillis);
        assertEquals(2_800L, finished.endMillis);
        assertTrue(finished.finalChunk);
        assertNull(accumulator.finishTurn());
    }

    @Test
    public void emptyFinalDecodeAdvancesTimestampAndFinalizesExistingText() {
        StreamingAsrTurnAccumulator accumulator = new StreamingAsrTurnAccumulator();
        accumulator.acceptDecoded("message", "en", 500L, 2_500L, false);

        StreamingAsrTurnAccumulator.Emission finished =
                accumulator.acceptDecoded("", "es", 2_500L, 3_100L, true);
        assertEquals("message", finished.text);
        assertEquals("en", finished.language);
        assertEquals(500L, finished.startMillis);
        assertEquals(3_100L, finished.endMillis);
        assertTrue(finished.finalChunk);
    }

    @Test
    public void endpointMarkerPreservesLastDecodedAudioBoundary() {
        StreamingAsrTurnAccumulator accumulator = new StreamingAsrTurnAccumulator();
        accumulator.acceptDecoded("message", "en", 1_000L, 3_000L, false);

        StreamingAsrTurnAccumulator.Emission finished = accumulator.finishTurn();
        assertEquals(3_000L, finished.endMillis);
    }

    @Test
    public void speechlessWindowsDoNotEmit() {
        StreamingAsrTurnAccumulator accumulator = new StreamingAsrTurnAccumulator();
        assertNull(accumulator.acceptDecoded("", "en", 0L, 2_000L, false));
        assertNull(accumulator.acceptDecoded("", "es", 2_000L, 2_600L, true));
        assertNull(accumulator.finishTurn());
    }

    @Test
    public void malformedDecodeMetadataFailsClosed() {
        StreamingAsrTurnAccumulator accumulator = new StreamingAsrTurnAccumulator();
        assertThrows(NullPointerException.class,
                () -> accumulator.acceptDecoded(null, "en", 0L, 1L, false));
        assertThrows(NullPointerException.class,
                () -> accumulator.acceptDecoded("word", null, 0L, 1L, false));
        assertThrows(IllegalArgumentException.class,
                () -> accumulator.acceptDecoded("word", "", 0L, 1L, false));
        assertThrows(IllegalArgumentException.class,
                () -> accumulator.acceptDecoded("word", "en", -1L, 1L, false));
        assertThrows(IllegalArgumentException.class,
                () -> accumulator.acceptDecoded("word", "en", 2L, 1L, false));
    }
}
