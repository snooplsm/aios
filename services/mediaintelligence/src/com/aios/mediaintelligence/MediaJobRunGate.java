package com.aios.mediaintelligence;

/** Identity-binds JobScheduler stop and finish callbacks to one worker run. */
final class MediaJobRunGate {
    enum Finish {
        COMPLETED,
        STOPPED,
        STALE
    }

    static final class Token {
        private final String deliveryId;
        private boolean stopped;

        private Token(String deliveryId) {
            this.deliveryId = deliveryId;
        }
    }

    private Token active;

    synchronized Token begin(String deliveryId) {
        if (deliveryId == null || deliveryId.isBlank() || active != null) return null;
        active = new Token(deliveryId);
        return active;
    }

    synchronized boolean stop(String deliveryId) {
        if (active == null || !active.deliveryId.equals(deliveryId) || active.stopped) {
            return false;
        }
        active.stopped = true;
        return true;
    }

    synchronized Finish finish(Token expected) {
        if (expected == null || active != expected) return Finish.STALE;
        active = null;
        return expected.stopped ? Finish.STOPPED : Finish.COMPLETED;
    }
}
