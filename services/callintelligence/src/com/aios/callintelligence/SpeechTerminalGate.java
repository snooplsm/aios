package com.aios.callintelligence;

/** Lets exactly one provider, Broker, or owner path claim a speech terminal state. */
final class SpeechTerminalGate {
    private boolean terminal;

    synchronized boolean claim() {
        if (terminal) return false;
        terminal = true;
        return true;
    }

    synchronized boolean isTerminal() {
        return terminal;
    }
}
