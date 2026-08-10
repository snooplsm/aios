package com.aios.modelbroker;

import android.os.SystemProperties;

/** Narrow platform-property adapter used only to gate research behavior. */
final class BrokerProductProperties {
    private BrokerProductProperties() {}

    static boolean isDebuggableBuild() {
        return SystemProperties.getInt("ro.debuggable", 0) == 1;
    }
}
