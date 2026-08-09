package com.aios.callintelligence;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Requires first successfully stored PCM from both telephony directions. */
final class RequiredCaptureGate {
    static final String DOWNLINK = "downlink";
    static final String UPLINK = "uplink";

    private final CountDownLatch terminal = new CountDownLatch(1);
    private boolean downlinkReady;
    private boolean uplinkReady;
    private String failure;

    synchronized void markReady(String direction) {
        if (isTerminal()) return;
        if (DOWNLINK.equals(direction)) {
            downlinkReady = true;
        } else if (UPLINK.equals(direction)) {
            uplinkReady = true;
        } else {
            markFailure(direction, "unknown_capture_direction");
            return;
        }
        if (downlinkReady && uplinkReady) terminal.countDown();
    }

    synchronized void markFailure(String direction, String reason) {
        if (isTerminal()) return;
        String safeDirection = DOWNLINK.equals(direction) || UPLINK.equals(direction)
                ? direction : "unknown";
        failure = reason == null || reason.isBlank()
                ? safeDirection + "_capture_unavailable" : reason;
        terminal.countDown();
    }

    String await(long timeoutMillis) {
        boolean signaled;
        try {
            signaled = timeoutMillis > 0L
                    && terminal.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "capture_startup_interrupted";
        }
        synchronized (this) {
            if (failure != null) return failure;
            if (downlinkReady && uplinkReady) return null;
            if (!signaled) {
                return !downlinkReady
                        ? "downlink_first_pcm_timeout"
                        : "uplink_first_pcm_timeout";
            }
            return "capture_startup_incomplete";
        }
    }

    private boolean isTerminal() {
        return failure != null || (downlinkReady && uplinkReady);
    }
}
