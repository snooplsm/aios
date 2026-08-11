package com.aios.modelbroker;

/** Bounds provider chunk output without imposing a short lifetime on live call ASR. */
final class SessionChunkPolicy {
    static final long MAX_BOUNDED_CHUNKS = 4_096L;
    static final long MAX_BOUNDED_CHARS = 4L * 1024L * 1024L;
    static final long CALL_TIMELINE_LEAD_MILLIS = 10_000L;
    static final long CALL_INITIAL_CHUNK_ALLOWANCE = 64L;
    static final long CALL_MIN_MILLIS_PER_CHUNK = 100L;

    private SessionChunkPolicy() {}

    static boolean accepts(
            String workload,
            boolean lifecycleBound,
            long acceptedChunks,
            long acceptedChars,
            int nextChars,
            long sourceEndMillis,
            long elapsedSessionMillis) {
        if (acceptedChunks < 0L || acceptedChars < 0L || nextChars < 0
                || sourceEndMillis < 0L || elapsedSessionMillis < 0L) {
            return false;
        }
        boolean liveCall = lifecycleBound
                && ("call_rx".equals(workload) || "call_tx".equals(workload));
        if (!liveCall) {
            return acceptedChunks < MAX_BOUNDED_CHUNKS
                    && acceptedChars <= MAX_BOUNDED_CHARS
                    && nextChars <= MAX_BOUNDED_CHARS - acceptedChars;
        }

        long latestSourceMillis = elapsedSessionMillis > Long.MAX_VALUE - CALL_TIMELINE_LEAD_MILLIS
                ? Long.MAX_VALUE : elapsedSessionMillis + CALL_TIMELINE_LEAD_MILLIS;
        if (sourceEndMillis > latestSourceMillis) {
            return false;
        }
        long timelineAllowance = sourceEndMillis / CALL_MIN_MILLIS_PER_CHUNK;
        long allowedChunks = timelineAllowance > Long.MAX_VALUE - CALL_INITIAL_CHUNK_ALLOWANCE
                ? Long.MAX_VALUE : timelineAllowance + CALL_INITIAL_CHUNK_ALLOWANCE;
        return acceptedChunks < allowedChunks;
    }
}
