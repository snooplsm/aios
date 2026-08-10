package com.aios.callintelligence;

import android.os.SystemProperties;

/** Narrow platform-property adapter; preview compile checks replace it with a fail-closed shim. */
final class CallProductProperties {
    private static final String CALL_UPLINK_VALIDATION_PROPERTY =
            "ro.aios.call_uplink_validated";

    private CallProductProperties() {}

    static boolean callerUplinkValidated() {
        return SystemProperties.getBoolean(CALL_UPLINK_VALIDATION_PROPERTY, false);
    }
}
