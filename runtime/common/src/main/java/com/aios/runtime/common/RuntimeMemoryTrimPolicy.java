package com.aios.runtime.common;

/** Interprets Android's separate running-process and cached-process trim families. */
public final class RuntimeMemoryTrimPolicy {
    private static final int RUNNING_LOW = 10;
    private static final int RUNNING_CRITICAL = 15;
    private static final int BACKGROUND = 40;

    private RuntimeMemoryTrimPolicy() {}

    public static boolean isMemoryPressure(int level) {
        return level == RUNNING_LOW || level == RUNNING_CRITICAL || level >= BACKGROUND;
    }
}
