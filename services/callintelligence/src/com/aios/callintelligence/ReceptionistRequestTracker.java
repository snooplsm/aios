package com.aios.callintelligence;

/**
 * Tracks one semantic receptionist request across transport replacements.
 * Recovery changes callback identity without extending the original deadline.
 */
final class ReceptionistRequestTracker {
    static final class Token {
        final long generation;
        final long deadlineElapsedRealtimeMillis;

        Token(long generation, long deadlineElapsedRealtimeMillis) {
            this.generation = generation;
            this.deadlineElapsedRealtimeMillis = deadlineElapsedRealtimeMillis;
        }
    }

    private long generation;
    private Token current;
    private boolean closed;

    synchronized Token begin(long nowElapsedRealtimeMillis, long timeoutMillis) {
        if (closed || current != null || nowElapsedRealtimeMillis < 0L || timeoutMillis <= 0L
                || generation == Long.MAX_VALUE) {
            return null;
        }
        final long deadline;
        try {
            deadline = Math.addExact(nowElapsedRealtimeMillis, timeoutMillis);
        } catch (ArithmeticException overflow) {
            return null;
        }
        current = new Token(++generation, deadline);
        return current;
    }

    synchronized Token recover(Token expected, long nowElapsedRealtimeMillis) {
        if (closed || current != expected || nowElapsedRealtimeMillis < 0L) return null;
        if (nowElapsedRealtimeMillis >= expected.deadlineElapsedRealtimeMillis
                || generation == Long.MAX_VALUE) {
            current = null;
            return null;
        }
        current = new Token(++generation, expected.deadlineElapsedRealtimeMillis);
        return current;
    }

    synchronized boolean isCurrent(Token expected) {
        return !closed && current == expected;
    }

    synchronized boolean complete(Token expected) {
        if (closed || current != expected) return false;
        current = null;
        return true;
    }

    synchronized boolean isActive() {
        return !closed && current != null;
    }

    synchronized void close() {
        closed = true;
        current = null;
    }
}
