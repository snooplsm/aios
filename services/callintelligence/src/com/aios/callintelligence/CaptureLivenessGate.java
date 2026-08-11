package com.aios.callintelligence;

/** Fences intentional capture shutdown from the first unexpected post-PCM loss. */
final class CaptureLivenessGate {
    static final class Failure {
        final String direction;
        final String reason;

        Failure(String direction, String reason) {
            this.direction = direction;
            this.reason = reason;
        }
    }

    private boolean closing;
    private boolean failureReported;

    synchronized Failure onDirectionStopped(
            String direction, boolean receivedPcm, String reason) {
        if (closing || failureReported || !receivedPcm) return null;
        String safeDirection = RequiredCaptureGate.DOWNLINK.equals(direction)
                || RequiredCaptureGate.UPLINK.equals(direction)
                ? direction : "unknown";
        String safeReason = reason == null || reason.isBlank()
                ? safeDirection + "_capture_lost" : reason;
        failureReported = true;
        return new Failure(safeDirection, safeReason);
    }

    synchronized void close() {
        closing = true;
    }
}
