package com.aios.callintelligence;

/** Prevents a receptionist greeting from replaying after service-loss recovery. */
final class AssistantGreetingPolicy {
    private AssistantGreetingPolicy() {}

    static boolean shouldGreet(boolean answeredByAi, boolean resumedAfterServiceLoss) {
        return answeredByAi && !resumedAfterServiceLoss;
    }
}
