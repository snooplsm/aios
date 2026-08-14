package com.aios.callintelligence;

/** Pure admission policy for optional, fail-open caller-history retrieval. */
final class CallerHistoryPolicy {
    private CallerHistoryPolicy() {}

    static boolean shouldPrepare(
            boolean enabled,
            boolean emergency,
            boolean emergencyCallbackMode,
            boolean processingAllowed,
            boolean conversationAllowed,
            String transientAddress) {
        return enabled
                && !emergency
                && !emergencyCallbackMode
                && processingAllowed
                && conversationAllowed
                && transientAddress != null
                && !transientAddress.isBlank();
    }
}
