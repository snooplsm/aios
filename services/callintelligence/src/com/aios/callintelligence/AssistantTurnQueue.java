package com.aios.callintelligence;

/** Small synchronized state machine that prevents overlapping receptionist output. */
final class AssistantTurnQueue {
    static final int MAX_PENDING_TEXT_CHARS = 2_048;

    static final class CallerTurn {
        final String language;
        final String text;

        CallerTurn(String language, String text) {
            this.language = language;
            this.text = text;
        }
    }

    private boolean busy;
    private boolean closed;
    private CallerTurn pending;

    synchronized boolean beginGreeting() {
        if (closed || busy) return false;
        busy = true;
        return true;
    }

    synchronized CallerTurn offer(String language, String text) {
        if (closed) return null;
        CallerTurn turn = new CallerTurn(language, bounded(text));
        if (busy) {
            pending = coalesce(pending, turn);
            return null;
        }
        busy = true;
        return turn;
    }

    synchronized CallerTurn complete() {
        if (closed) return null;
        CallerTurn next = pending;
        pending = null;
        busy = next != null;
        return next;
    }

    synchronized boolean isBusy() {
        return busy && !closed;
    }

    synchronized void close() {
        closed = true;
        busy = false;
        pending = null;
    }

    private static CallerTurn coalesce(CallerTurn current, CallerTurn latest) {
        if (current == null || current.text.isEmpty()) return latest;
        if (latest.text.isEmpty()) return current;
        return new CallerTurn(
                latest.language,
                bounded(current.text + " " + latest.text));
    }

    private static String bounded(String value) {
        String normalized = value == null ? "" : value.trim();
        int excess = normalized.length() - MAX_PENDING_TEXT_CHARS;
        if (excess <= 0) return normalized;
        int boundary = normalized.indexOf(' ', excess);
        int start = boundary >= 0 && boundary + 1 < normalized.length()
                ? boundary + 1
                : excess;
        return normalized.substring(start);
    }
}
