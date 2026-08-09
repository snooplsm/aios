package com.aios.callintelligence;

import java.util.Set;

/** Pure validation policy for caller-facing model output. */
final class ReceptionistReplyPolicy {
    private static final int MAX_REPLY_CHARS = 512;
    private static final int MAX_REASON_CHARS = 64;
    private static final Set<String> LANGUAGES = Set.of("en", "es");
    private static final Set<String> LABELS = Set.of(
            SpamRiskEngine.LIKELY_LEGITIMATE,
            SpamRiskEngine.UNKNOWN,
            SpamRiskEngine.SUSPICIOUS,
            SpamRiskEngine.HIGH_RISK);

    private ReceptionistReplyPolicy() {}

    static boolean accepts(
            String text,
            String language,
            String requestedLanguage,
            int score,
            String label,
            String reason) {
        if (text == null || text.isBlank() || text.length() > MAX_REPLY_CHARS
                || hasControlCharacter(text) || !LANGUAGES.contains(requestedLanguage)
                || !requestedLanguage.equals(language)
                || !LANGUAGES.contains(language) || score < 0 || score > 100
                || !LABELS.contains(label) || !labelMatchesScore(label, score)
                || reason == null
                || !reason.matches("[a-z0-9_]{1," + MAX_REASON_CHARS + "}")) {
            return false;
        }
        return true;
    }

    private static boolean hasControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) return true;
        }
        return false;
    }

    private static boolean labelMatchesScore(String label, int score) {
        if (SpamRiskEngine.HIGH_RISK.equals(label)) return score >= 75;
        if (SpamRiskEngine.SUSPICIOUS.equals(label)) return score >= 50 && score < 75;
        if (SpamRiskEngine.LIKELY_LEGITIMATE.equals(label)) return score <= 15;
        return SpamRiskEngine.UNKNOWN.equals(label) && score < 50;
    }
}
