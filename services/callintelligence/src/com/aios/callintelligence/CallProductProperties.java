package com.aios.callintelligence;

import android.os.SystemProperties;

/** Narrow platform-property adapter; preview compile checks replace it with a fail-closed shim. */
final class CallProductProperties {
    private static final String CALL_UPLINK_VALIDATION_PROPERTY =
            "ro.aios.call_uplink_validated";
    private static final String DEVELOPMENT_CALL_UPLINK_TEST_PROPERTY =
            "persist.aios.debug.call_uplink_test";

    private CallProductProperties() {}

    static boolean callerUplinkValidated() {
        return SystemProperties.getBoolean(CALL_UPLINK_VALIDATION_PROPERTY, false);
    }

    static boolean developmentUplinkTestActive() {
        return CallerUplinkAdmission.developmentTestActive(
                callerUplinkValidated(),
                SystemProperties.getBoolean("ro.debuggable", false),
                SystemProperties.getBoolean(DEVELOPMENT_CALL_UPLINK_TEST_PROPERTY, false));
    }

    static boolean manualCallerUplinkAllowed() {
        return callerUplinkValidated() || developmentUplinkTestActive();
    }
}
