package com.aios.callintelligence;

/** Bounded, final-segment-only call summary suitable for the local context index. */
final class CallContextAccumulator {
    static final int MAX_DOCUMENT_CHARS = 4_096;
    private static final int MAX_EVENT_CHARS = 1_024;

    private final StringBuilder value = new StringBuilder();

    synchronized void appendTranscript(
            String direction, String language, String text, boolean isFinal) {
        if (!isFinal || !("downlink".equals(direction) || "uplink".equals(direction))
                || !("en".equals(language) || "es".equals(language))) {
            return;
        }
        append(direction + "[" + language + "]: ", text);
    }

    synchronized void appendAssistantReply(String language, String text) {
        if (!("en".equals(language) || "es".equals(language))) return;
        append("assistant[" + language + "]: ", text);
    }

    synchronized void appendAssessment(int score, String label, String reasonCode) {
        if (score < 0 || score > 100 || label == null || reasonCode == null
                || !label.matches("[a-z_]{1,32}")
                || !reasonCode.matches("[a-z0-9_]{1,64}")) {
            return;
        }
        append("risk: ", score + " " + label + " " + reasonCode);
    }

    synchronized String finish(int disconnectCause) {
        String terminal = "call_end: disconnect_cause=" + disconnectCause;
        StringBuilder result = new StringBuilder(value);
        appendBounded(result, terminal + "\n");
        return result.toString().trim();
    }

    private void append(String prefix, String raw) {
        String normalized = PriorContextFormatter.normalize(raw, MAX_EVENT_CHARS);
        if (normalized.isEmpty()) return;
        appendBounded(value, prefix + normalized + "\n");
    }

    private static void appendBounded(StringBuilder destination, String addition) {
        destination.append(addition);
        int excess = destination.length() - MAX_DOCUMENT_CHARS;
        if (excess > 0) destination.delete(0, excess);
    }
}
