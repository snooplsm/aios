package com.aios.callintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AnswerDelayPolicyTest {
    @Test
    public void fixedModesResolveExactly() {
        assertEquals(1_000L, new AnswerDelayPolicy(
                AnswerDelayPolicy.FIXED_1_SECOND).nextDelayMillis());
        assertEquals(2_000L, new AnswerDelayPolicy(
                AnswerDelayPolicy.FIXED_2_SECONDS).nextDelayMillis());
        assertEquals(3_000L, new AnswerDelayPolicy(
                AnswerDelayPolicy.FIXED_3_SECONDS).nextDelayMillis());
        assertEquals(4_000L, new AnswerDelayPolicy(
                AnswerDelayPolicy.FIXED_4_SECONDS).nextDelayMillis());
    }

    @Test
    public void randomModeUsesInclusiveRequestedBounds() {
        long[] observed = new long[2];
        AnswerDelayPolicy policy = new AnswerDelayPolicy(
                AnswerDelayPolicy.RANDOM_1_01_TO_3_99_SECONDS,
                (origin, bound) -> {
                    observed[0] = origin;
                    observed[1] = bound;
                    return bound - 1L;
                });

        assertEquals(3_990L, policy.nextDelayMillis());
        assertEquals(1_010L, observed[0]);
        assertEquals(3_991L, observed[1]);
    }

    @Test
    public void unknownModeFallsBackToTwoSeconds() {
        assertEquals(2_000L, new AnswerDelayPolicy("unknown").nextDelayMillis());
        assertFalse(AnswerDelayPolicy.isKnownMode("unknown"));
        assertTrue(AnswerDelayPolicy.isKnownMode(
                AnswerDelayPolicy.RANDOM_1_01_TO_3_99_SECONDS));
    }
}
