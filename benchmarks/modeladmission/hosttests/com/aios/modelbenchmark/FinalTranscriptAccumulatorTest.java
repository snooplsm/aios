package com.aios.modelbenchmark;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class FinalTranscriptAccumulatorTest {
    @Test
    public void finalizedTurnsAppendWhilePartialsReplaceOnlyLiveState() {
        FinalTranscriptAccumulator value = new FinalTranscriptAccumulator();

        value.accept("first partial", false, 0L, 1_000L);
        value.accept("first turn", true, 0L, 1_500L);
        value.accept("second partial", false, 2_000L, 3_000L);
        value.accept("second turn", true, 2_000L, 3_500L);

        assertEquals("first turn second turn", value.valueOr("live tail"));
    }

    @Test
    public void staleOrOverlappingFinalCannotDuplicateTranscript() {
        FinalTranscriptAccumulator value = new FinalTranscriptAccumulator();

        value.accept("accepted", true, 500L, 2_000L);
        value.accept("stale", true, 500L, 2_000L);
        value.accept("overlap", true, 1_500L, 2_500L);

        assertEquals("accepted", value.valueOr("fallback"));
    }

    @Test
    public void liveFallbackIsUsedBeforeAnyFinalTurn() {
        FinalTranscriptAccumulator value = new FinalTranscriptAccumulator();

        value.accept("partial", false, 0L, 1_000L);

        assertEquals("latest partial", value.valueOr(" latest partial "));
    }
}
