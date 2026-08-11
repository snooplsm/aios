package com.aios.callintelligence;

/** Binds asynchronous classifier output to the newest accepted ASR sequence. */
final class TranscriptRevisionGate {
    static final long UNBOUND = -1L;

    private long latest = UNBOUND;

    synchronized boolean advance(long candidate) {
        if (candidate < 0L || candidate <= latest) return false;
        latest = candidate;
        return true;
    }

    synchronized boolean accepts(long candidate) {
        return candidate == UNBOUND || candidate == latest;
    }

    synchronized long current() {
        return latest;
    }
}
