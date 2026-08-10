package com.aios.contextintelligence;

/** Monotonic revision rule preventing stale writes from reviving deleted context. */
final class RevisionGate {
    private RevisionGate() {}

    static boolean accepts(long incomingRevision, long currentRevision, long tombstoneRevision) {
        if (incomingRevision <= 0L || currentRevision < 0L || tombstoneRevision < 0L) {
            throw new IllegalArgumentException("invalid context revision");
        }
        return incomingRevision > currentRevision && incomingRevision > tombstoneRevision;
    }
}
