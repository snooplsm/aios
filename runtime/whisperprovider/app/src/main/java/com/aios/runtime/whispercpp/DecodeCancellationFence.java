package com.aios.runtime.whispercpp;

/** Owns one native decode-cancellation token without racing cancel against destroy. */
final class DecodeCancellationFence {
    interface NativeSignal {
        void cancel(long token);
        void destroy(long token);
    }

    private boolean terminal;
    private boolean cancellationSent;
    private long activeToken;

    synchronized void attach(long token, NativeSignal signal) {
        if (token <= 0L || signal == null) {
            throw new IllegalArgumentException("valid token and native signal required");
        }
        if (activeToken != 0L) {
            throw new IllegalStateException("a decode token is already active");
        }
        activeToken = token;
        cancellationSent = false;
        if (terminal) cancelActive(signal);
    }

    synchronized void cancel(NativeSignal signal) {
        if (signal == null) throw new IllegalArgumentException("native signal required");
        terminal = true;
        cancelActive(signal);
    }

    synchronized void finish(long token, NativeSignal signal) {
        if (signal == null) throw new IllegalArgumentException("native signal required");
        if (token <= 0L || activeToken != token) {
            throw new IllegalStateException("decode token is not current");
        }
        activeToken = 0L;
        cancellationSent = false;
        signal.destroy(token);
    }

    private void cancelActive(NativeSignal signal) {
        if (activeToken == 0L || cancellationSent) return;
        cancellationSent = true;
        signal.cancel(activeToken);
    }
}
