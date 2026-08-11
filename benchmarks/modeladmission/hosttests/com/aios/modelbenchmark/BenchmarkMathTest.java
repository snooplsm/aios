package com.aios.modelbenchmark;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class BenchmarkMathTest {
    @Test
    public void percentileUsesNearestRank() {
        assertEquals(50.0, BenchmarkMath.percentileLong(
                List.of(10L, 20L, 30L, 40L, 50L), 0.95), 0.0);
        assertEquals(2.0, BenchmarkMath.percentileDouble(
                List.of(1.0, 2.0, 3.0, 4.0), 0.5), 0.0);
    }

    @Test
    public void wordErrorRateNormalizesPunctuationAndAccents() {
        assertEquals(0.0, BenchmarkMath.wordErrorRate(
                "Gracias por llamar", "¡Gracias, por llamar!"), 0.0);
        assertEquals(1.0 / 3.0, BenchmarkMath.wordErrorRate(
                "please call tomorrow", "please text tomorrow"), 0.0001);
    }

    @Test
    public void knownAnswerUsesWholeNormalizedWords() {
        assertTrue(BenchmarkMath.containsNormalizedWord("The answer is cinco.", "cinco"));
        assertEquals(3, BenchmarkMath.approximateTokens("one, two three"));
    }

    @Test
    public void liveAsrLagIsRelativeToAvailableSourceAudio() {
        assertEquals(500L, BenchmarkMath.sourceRelativeLagOrTimeout(
                2_600L, 100L, 2_000L, 120_000L));
        assertEquals(0L, BenchmarkMath.sourceRelativeLagOrTimeout(
                2_000L, 100L, 2_000L, 120_000L));
        assertEquals(1_200L, BenchmarkMath.endpointDelayOrTimeout(
                3_300L, 100L, 2_000L, 120_000L));
        assertEquals(2_000L, BenchmarkMath.sourceSpanOrTimeout(
                500L, 2_500L, 120_000L));
    }

    @Test
    public void missingAsrTimingUsesTheFailureSentinel() {
        assertEquals(120_000L, BenchmarkMath.sourceRelativeLagOrTimeout(
                0L, 100L, 2_000L, 120_000L));
        assertEquals(120_000L, BenchmarkMath.endpointDelayOrTimeout(
                3_000L, 0L, 2_000L, 120_000L));
        assertEquals(120_000L, BenchmarkMath.sourceSpanOrTimeout(
                -1L, 2_000L, 120_000L));
    }
}
