package com.aios.callintelligence;

/** Maps stream-local 16 kHz PCM timestamps onto the uninterrupted captured-call timeline. */
final class PcmTranscriptTimeline {
    static final class Span {
        final long startMillis;
        final long endMillis;

        Span(long startMillis, long endMillis) {
            this.startMillis = startMillis;
            this.endMillis = endMillis;
        }
    }

    private static final long SAMPLE_RATE_HZ = 16_000L;
    private static final long PCM16_BYTES_PER_SAMPLE = 2L;

    private Object activeStream;
    private long offsetMillis;

    synchronized boolean activate(Object streamIdentity, long capturedPcmBytes) {
        if (streamIdentity == null || capturedPcmBytes < 0L) {
            return false;
        }
        long samples = capturedPcmBytes / PCM16_BYTES_PER_SAMPLE;
        long seconds = samples / SAMPLE_RATE_HZ;
        long remainderMillis = samples % SAMPLE_RATE_HZ * 1_000L / SAMPLE_RATE_HZ;
        if (seconds > (Long.MAX_VALUE - remainderMillis) / 1_000L) return false;
        long candidateOffset = seconds * 1_000L + remainderMillis;
        if (activeStream == streamIdentity) return offsetMillis == candidateOffset;
        activeStream = streamIdentity;
        offsetMillis = candidateOffset;
        return true;
    }

    synchronized boolean deactivate(Object streamIdentity) {
        if (streamIdentity == null || activeStream != streamIdentity) return false;
        activeStream = null;
        offsetMillis = 0L;
        return true;
    }

    synchronized Span map(Object streamIdentity, long sourceStartMillis, long sourceEndMillis) {
        if (streamIdentity == null || activeStream != streamIdentity
                || sourceStartMillis < 0L || sourceEndMillis < sourceStartMillis
                || sourceStartMillis > Long.MAX_VALUE - offsetMillis
                || sourceEndMillis > Long.MAX_VALUE - offsetMillis) {
            return null;
        }
        return new Span(
                offsetMillis + sourceStartMillis,
                offsetMillis + sourceEndMillis);
    }
}
