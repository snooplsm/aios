package com.aios.callintelligence;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AssistantGreetingPolicyTest {
    @Test
    public void freshAiAnswerGreetsExactlyOnce() {
        assertTrue(AssistantGreetingPolicy.shouldGreet(true, false));
    }

    @Test
    public void resumedAiSessionDoesNotReplayGreeting() {
        assertFalse(AssistantGreetingPolicy.shouldGreet(true, true));
    }

    @Test
    public void ownerHandledCallNeverGreets() {
        assertFalse(AssistantGreetingPolicy.shouldGreet(false, false));
        assertFalse(AssistantGreetingPolicy.shouldGreet(false, true));
    }
}
