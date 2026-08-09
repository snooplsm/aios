package com.aios.callintelligence;

import com.aios.call.CallHandlingDecision;
import com.aios.call.IncomingCallContext;

/** Pure deterministic policy; no model output may decide whether Telecom answers. */
final class CallPolicyEngine {
    static final String MODE_OFF = "off";
    static final String MODE_MISSED_ONLY = "missed_only";
    static final String MODE_UNKNOWN_ONLY = "unknown_only";
    static final String MODE_ALL = "all";

    private final String mode;
    private final long missedCallDelayMillis;
    private final AnswerDelayPolicy automaticAnswerDelay;
    private final boolean processingEnabled;

    CallPolicyEngine(
            String mode,
            long missedCallDelayMillis,
            String answerDelayMode,
            boolean processingEnabled) {
        this.mode = isKnownMode(mode) ? mode : MODE_OFF;
        this.missedCallDelayMillis = clampDelay(missedCallDelayMillis);
        this.automaticAnswerDelay = new AnswerDelayPolicy(answerDelayMode);
        this.processingEnabled = processingEnabled;
    }

    CallHandlingDecision evaluate(IncomingCallContext context) {
        if (context == null) {
            return decision(
                    CallHandlingDecision.ACTION_BYPASS_AI,
                    0L,
                    false,
                    false,
                    "missing_context");
        }
        if (context.emergency || context.emergencyCallbackMode) {
            return decision(
                    CallHandlingDecision.ACTION_BYPASS_AI,
                    0L,
                    false,
                    false,
                    "emergency_bypass");
        }
        switch (mode) {
            case MODE_ALL:
                return aiDecision(
                        CallHandlingDecision.ACTION_ANSWER_WITH_AI,
                        automaticAnswerDelay.nextDelayMillis(),
                        "owner_policy_all");
            case MODE_UNKNOWN_ONLY:
                if (!context.knownContact) {
                    return aiDecision(
                            CallHandlingDecision.ACTION_ANSWER_WITH_AI,
                            automaticAnswerDelay.nextDelayMillis(),
                            "owner_policy_unknown");
                }
                return decision(
                        CallHandlingDecision.ACTION_RING_OWNER,
                        0L,
                        false,
                        processingEnabled,
                        "known_contact");
            case MODE_MISSED_ONLY:
                return aiDecision(
                        CallHandlingDecision.ACTION_RING_THEN_AI,
                        missedCallDelayMillis,
                        "owner_policy_missed");
            case MODE_OFF:
            default:
                return decision(
                        CallHandlingDecision.ACTION_RING_OWNER,
                        0L,
                        false,
                        processingEnabled,
                        "owner_policy_off");
        }
    }

    private CallHandlingDecision aiDecision(int action, long delayMillis, String reason) {
        if (!processingEnabled) {
            return decision(
                    CallHandlingDecision.ACTION_RING_OWNER,
                    0L,
                    false,
                    processingEnabled,
                    "assistant_not_ready");
        }
        return decision(action, delayMillis, true, processingEnabled, reason);
    }

    private static CallHandlingDecision decision(
            int action,
            long delayMillis,
            boolean mayAnswer,
            boolean processingAllowed,
            String reason) {
        CallHandlingDecision value = new CallHandlingDecision();
        value.action = action;
        value.answerDelayMillis = delayMillis;
        value.aiMayAnswer = mayAnswer;
        value.processingAllowed = processingAllowed;
        value.reason = reason;
        return value;
    }

    static boolean isKnownMode(String candidate) {
        return MODE_OFF.equals(candidate)
                || MODE_MISSED_ONLY.equals(candidate)
                || MODE_UNKNOWN_ONLY.equals(candidate)
                || MODE_ALL.equals(candidate);
    }

    static long clampDelay(long value) {
        return Math.max(3_000L, Math.min(value, 60_000L));
    }
}
