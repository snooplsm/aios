package com.aios.callintelligence;

/** Recognizes completion of the exact pre-answer greeting synthesis request. */
final class AssistantGreetingPrewarmPolicy {
    private static final String REQUEST_MARKER = ":tts:preanswer:";

    private AssistantGreetingPrewarmPolicy() {}

    static boolean shouldPrewarmReceptionist(
            String callId, String requestId, String detail) {
        return callId != null && !callId.isEmpty()
                && requestId != null
                && requestId.startsWith(callId + REQUEST_MARKER)
                && "speech_synthesis_complete".equals(detail);
    }
}
