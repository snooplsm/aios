package com.aios.callintelligence;

/**
 * Bounded caller transcript with one replaceable live hypothesis.
 *
 * <p>Whisper revisions describe the current turn from its start. Appending every
 * revision would duplicate words and let corrected text survive in a model
 * prompt. Final turns are therefore committed, while the newest partial is kept
 * separately and replaced atomically. The Model Broker's validated chunk
 * sequence is the revision clock, so stale callbacks cannot move state backward.</p>
 */
final class IncrementalCallerTranscript {
    static final class Snapshot {
        final String text;
        final String language;
        final long revision;
        final boolean isFinal;

        Snapshot(String text, String language, long revision, boolean isFinal) {
            this.text = text;
            this.language = language;
            this.revision = revision;
            this.isFinal = isFinal;
        }
    }

    private final int maxChars;
    private final StringBuilder committed = new StringBuilder();
    private String partial = "";
    private String language = "";
    private long revision = -1L;
    private boolean finalSnapshot = true;

    IncrementalCallerTranscript(int maxChars) {
        if (maxChars < 64) throw new IllegalArgumentException("transcript bound is too small");
        this.maxChars = maxChars;
    }

    synchronized boolean observe(
            String candidateLanguage, String text, boolean isFinal, long sourceRevision) {
        if (!("en".equals(candidateLanguage) || "es".equals(candidateLanguage))
                || text == null || text.isBlank()
                || sourceRevision < 0L || sourceRevision <= revision) {
            return false;
        }
        String normalized = text.trim();
        String line = "[" + candidateLanguage + "]["
                + (isFinal ? "final" : "partial") + "] " + normalized + "\n";
        if (isFinal) {
            appendBounded(committed, line, maxChars);
            partial = "";
        } else {
            partial = line;
        }
        language = candidateLanguage;
        finalSnapshot = isFinal;
        revision = sourceRevision;
        return true;
    }

    synchronized Snapshot snapshot() {
        String combined = committed.toString() + partial;
        if (combined.length() > maxChars) {
            combined = combined.substring(combined.length() - maxChars);
        }
        return new Snapshot(combined, language, revision, finalSnapshot);
    }

    private static void appendBounded(StringBuilder target, String addition, int maxChars) {
        target.append(addition);
        int excess = target.length() - maxChars;
        if (excess > 0) target.delete(0, excess);
    }
}
