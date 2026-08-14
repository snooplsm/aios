package com.aios.modelbenchmark;

/** Accumulates non-overlapping finalized ASR turns while ignoring live revisions. */
final class FinalTranscriptAccumulator {
    private final StringBuilder text = new StringBuilder();
    private long lastFinalSourceEndMillis = -1L;

    synchronized void accept(
            String value, boolean isFinal, long sourceStartMillis, long sourceEndMillis) {
        String normalized = value == null ? "" : value.trim();
        if (!isFinal || normalized.isEmpty() || sourceStartMillis < 0L
                || sourceEndMillis <= sourceStartMillis
                || (lastFinalSourceEndMillis >= 0L
                && sourceStartMillis < lastFinalSourceEndMillis)) {
            return;
        }
        if (text.length() > 0) text.append(' ');
        text.append(normalized);
        lastFinalSourceEndMillis = sourceEndMillis;
    }

    synchronized String valueOr(String fallback) {
        if (text.length() > 0) return text.toString();
        return fallback == null ? "" : fallback.trim();
    }
}
