package com.aios.callintelligence;

/** Decides which TTS statuses must stop an attached caller-audio pipe. */
final class SpeechSynthesisStatusPolicy {
    private static final String ERROR_PREFIX = "speech_synthesis_error_";

    private SpeechSynthesisStatusPolicy() {}

    static boolean terminatesCallerAudio(String detail) {
        return "speech_synthesis_broker_disconnected".equals(detail)
                || (detail != null && detail.startsWith(ERROR_PREFIX));
    }
}
