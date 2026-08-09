package com.aios.callintelligence;

/** Small synchronized state machine that prevents overlapping receptionist output. */
final class AssistantTurnQueue {
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
        CallerTurn turn = new CallerTurn(language, text);
        if (busy) {
            pending = turn;
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
}
