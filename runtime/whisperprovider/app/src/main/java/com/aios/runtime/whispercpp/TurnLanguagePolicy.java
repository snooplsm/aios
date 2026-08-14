package com.aios.runtime.whispercpp;

import java.util.Objects;

/** Detects language once per live utterance, then constrains its remaining windows. */
final class TurnLanguagePolicy {
    private static final String AUTO = "auto";
    private String decoderLanguage = AUTO;

    String decoderLanguage() {
        return decoderLanguage;
    }

    String acceptDecoderResult(String reportedLanguage) {
        Objects.requireNonNull(reportedLanguage, "reportedLanguage");
        if (!AUTO.equals(decoderLanguage)) {
            // Whisper was explicitly constrained for this window. Its language ID is
            // metadata for that forced decode, not a new independent detection vote.
            return decoderLanguage;
        }
        if (!"en".equals(reportedLanguage) && !"es".equals(reportedLanguage)) {
            throw new IllegalArgumentException(
                    "detected language is outside English/Spanish policy");
        }
        decoderLanguage = reportedLanguage;
        return decoderLanguage;
    }

    void finishTurn() {
        decoderLanguage = AUTO;
    }
}
