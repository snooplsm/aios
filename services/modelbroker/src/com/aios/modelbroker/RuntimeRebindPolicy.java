package com.aios.modelbroker;

/** Bounded exponential retry state for a verified runtime-service binding. */
final class RuntimeRebindPolicy {
    static final long INITIAL_DELAY_MILLIS = 1_000L;
    static final long MAX_DELAY_MILLIS = 60_000L;
    static final long NO_RETRY = -1L;

    private long nextDelayMillis = INITIAL_DELAY_MILLIS;
    private boolean scheduled;
    private boolean closed;

    synchronized long reserve(boolean immediate) {
        if (closed || scheduled) {
            return NO_RETRY;
        }
        scheduled = true;
        if (immediate) {
            return 0L;
        }
        long delay = nextDelayMillis;
        nextDelayMillis = Math.min(MAX_DELAY_MILLIS, nextDelayMillis * 2L);
        return delay;
    }

    synchronized boolean begin() {
        if (closed || !scheduled) {
            return false;
        }
        scheduled = false;
        return true;
    }

    synchronized void connected() {
        scheduled = false;
        nextDelayMillis = INITIAL_DELAY_MILLIS;
    }

    synchronized void close() {
        closed = true;
        scheduled = false;
    }
}
