package com.aios.callintelligence;

/** Maps provider-local ASR sequences onto one monotonic revision clock for a call. */
final class CallTranscriptRevisionClock {
    static final long UNACCEPTED = -1L;

    private Object activeStream;
    private long lastSourceRevision = UNACCEPTED;
    private long nextCallRevision;

    synchronized boolean activate(Object streamIdentity) {
        if (streamIdentity == null) return false;
        if (activeStream == streamIdentity) return true;
        activeStream = streamIdentity;
        lastSourceRevision = UNACCEPTED;
        return true;
    }

    synchronized boolean deactivate(Object streamIdentity) {
        if (streamIdentity == null || activeStream != streamIdentity) return false;
        activeStream = null;
        lastSourceRevision = UNACCEPTED;
        return true;
    }

    synchronized long advance(Object streamIdentity, long sourceRevision) {
        if (streamIdentity == null || activeStream != streamIdentity
                || sourceRevision < 0L || sourceRevision <= lastSourceRevision
                || nextCallRevision == Long.MAX_VALUE) {
            return UNACCEPTED;
        }
        lastSourceRevision = sourceRevision;
        return nextCallRevision++;
    }
}
