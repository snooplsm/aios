package com.aios.callintelligence;

/** Compile-check substitute; production reads the immutable product property and fails closed. */
final class CallProductProperties {
    private CallProductProperties() {}

    static boolean callerUplinkValidated() {
        return false;
    }

    static boolean developmentUplinkTestActive() {
        return false;
    }

    static boolean manualCallerUplinkAllowed() {
        return false;
    }
}
