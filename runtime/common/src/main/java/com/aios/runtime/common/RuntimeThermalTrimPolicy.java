package com.aios.runtime.common;

/** Decides when an idle runtime must trade model warmth for thermal recovery. */
public final class RuntimeThermalTrimPolicy {
    private static final int THERMAL_STATUS_SEVERE = 3;
    private static final int THERMAL_STATUS_SHUTDOWN = 6;

    private RuntimeThermalTrimPolicy() {}

    public static boolean isThermalPressure(int status) {
        return status >= THERMAL_STATUS_SEVERE && status <= THERMAL_STATUS_SHUTDOWN;
    }
}
