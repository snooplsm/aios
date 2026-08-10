package com.aios.callintelligence;

/** Pure admission rule preventing Telecom answer before caller interaction is ready. */
final class AutomaticAnswerGate {
    private AutomaticAnswerGate() {}

    static boolean mayAnswer(boolean policyAllows, boolean callerInteractionReady) {
        return policyAllows && callerInteractionReady;
    }
}
