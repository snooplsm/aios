package com.aios.mediaintelligence;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Owns one media inference terminal result and its exact Broker session. */
final class MediaInferenceAttempt<T> {
    static final long NO_SESSION = -1L;

    static final class Snapshot<T> {
        final T result;
        final int errorCode;
        final String reason;
        final boolean cancelled;

        Snapshot(T result, int errorCode, String reason, boolean cancelled) {
            this.result = result;
            this.errorCode = errorCode;
            this.reason = reason;
            this.cancelled = cancelled;
        }
    }

    private final CountDownLatch terminal = new CountDownLatch(1);
    private boolean finished;
    private boolean cancelled;
    private T result;
    private int errorCode;
    private String reason;
    private long sessionId = NO_SESSION;
    private boolean submitted;

    synchronized boolean attachSession(long candidate) {
        if (candidate <= 0L || finished || sessionId != NO_SESSION) return false;
        sessionId = candidate;
        return true;
    }

    synchronized boolean isActive() {
        return !finished;
    }

    synchronized boolean markSubmitted() {
        if (finished || sessionId == NO_SESSION || submitted) return false;
        submitted = true;
        return true;
    }

    boolean complete(T value) {
        if (value == null) return fail(0, "model_runtime_empty_result");
        synchronized (this) {
            if (finished) return false;
            if (!submitted) {
                finished = true;
                reason = "model_result_before_submission";
                sessionId = NO_SESSION;
                terminal.countDown();
                return false;
            }
            finished = true;
            result = value;
            sessionId = NO_SESSION;
        }
        terminal.countDown();
        return true;
    }

    boolean fail(int code, String failureReason) {
        if (failureReason == null || failureReason.isBlank()) {
            throw new IllegalArgumentException("failure reason is required");
        }
        synchronized (this) {
            if (finished) return false;
            finished = true;
            errorCode = code;
            reason = failureReason;
            sessionId = NO_SESSION;
        }
        terminal.countDown();
        return true;
    }

    long cancel(int code, String cancellationReason) {
        if (cancellationReason == null || cancellationReason.isBlank()) {
            throw new IllegalArgumentException("cancellation reason is required");
        }
        long ownedSession;
        synchronized (this) {
            if (finished) return NO_SESSION;
            finished = true;
            cancelled = true;
            errorCode = code;
            reason = cancellationReason;
            ownedSession = sessionId;
            sessionId = NO_SESSION;
        }
        terminal.countDown();
        return ownedSession;
    }

    boolean await(long timeout, TimeUnit unit) throws InterruptedException {
        return terminal.await(timeout, unit);
    }

    long remainingSession() {
        synchronized (this) {
            return sessionId;
        }
    }

    synchronized Snapshot<T> snapshot() {
        if (!finished) throw new IllegalStateException("attempt is not terminal");
        return new Snapshot<>(result, errorCode, reason, cancelled);
    }
}
